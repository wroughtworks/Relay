package dev.relay.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The proxy's own SQLite file, and the one thread allowed to write to it.
 *
 * <p>Spec &sect;9.5 asks for SQLite via JDBC, and is equally clear about what it is not:
 * network-wide shared state. This is one node's record of what happened on it.
 *
 * <h2>Why the proxy owns this and not the dashboard</h2>
 * The dashboard is the thing that reads history, so putting the database there looks
 * natural and is wrong twice over. It would only record while a dashboard happened to be
 * running, so restarting the page would punch holes in the history; and bans and whitelist
 * &mdash; the other things &sect;9.5 names &mdash; have to be answerable during login, when
 * a companion may be down, restarting, or absent entirely. State a player's connection
 * depends on cannot live in an optional process.
 *
 * <h2>Never on an event loop</h2>
 * Every write goes through one queue and one thread. A {@code fsync} on a Netty thread
 * stalls every connection that thread carries, and the whole point of recording history is
 * that it is worth less than the traffic. If the queue fills, writes are <b>dropped</b> and
 * counted rather than blocking the caller: losing a row from a history table is a much
 * smaller problem than pausing a proxy to write one.
 */
public final class Database implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Database.class);

    /**
     * How many pending writes are held before new ones are dropped.
     *
     * <p>Sized for a burst -- a hundred players reconnecting after a restart -- not for a
     * sustained backlog. If this is ever full for long, the disk is the problem and
     * queueing more would only delay finding that out.
     */
    private static final int QUEUE_CAPACITY = 2048;

    private final Path file;
    private final AtomicBoolean running = new AtomicBoolean();
    private final BlockingQueue<Write> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong written = new AtomicLong();

    private Connection connection;
    private Thread writer;

    /** One statement and the values to bind, named for what it does when it fails. */
    private record Write(String what, String sql, List<Object> parameters) {
    }

    public Database(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public long dropped() {
        return dropped.get();
    }

    public long written() {
        return written.get();
    }

    /**
     * Opens the file and brings the schema up to date.
     *
     * @throws SQLException if the database cannot be opened or migrated. The caller
     *                      decides whether that is fatal; for Relay it is not -- a proxy
     *                      that will not start because a history table is unavailable
     *                      would be trading the job for the paperwork
     */
    public void start() throws SQLException {
        connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            // WAL so a reader is never blocked by the writer, and NORMAL because these
            // are observations: losing the last few on a power cut costs nothing worth
            // an fsync per row.
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
            statement.execute("PRAGMA busy_timeout=3000");
            statement.execute("PRAGMA foreign_keys=ON");
        }
        Schema.migrate(connection);

        running.set(true);
        writer = new Thread(this::drain, "relay-store");
        writer.setDaemon(true);
        writer.start();
        LOG.info("Storage    {} (schema {})", file, Schema.version(connection));
    }

    /**
     * Queues a write, or drops it.
     *
     * @param what a short description used only when something goes wrong, so a failing
     *             statement says which feature broke rather than printing SQL at an
     *             operator
     */
    public void submit(String what, String sql, Object... parameters) {
        if (!running.get()) {
            return;
        }
        // Arrays.asList, not List.of: a null parameter is an ordinary thing to write --
        // a backend that is healthy has no health detail, a player who connected by IP
        // has no virtual host -- and List.of rejects nulls. It did, silently, for every
        // health change until a stack trace in the proxy log said so.
        if (!queue.offer(new Write(what, sql, java.util.Arrays.asList(parameters)))) {
            long total = dropped.incrementAndGet();
            // Once per thousand: a full queue means the disk is behind, and a log line per
            // dropped row would turn a slow disk into a flooded log.
            if (total % 1000 == 1) {
                LOG.warn("Storage queue is full; dropped {} record(s) so far. History will "
                        + "have gaps. Player traffic is unaffected.", total);
            }
        }
    }

    /**
     * Reads, on the calling thread.
     *
     * <p>Reads are synchronous because they are made by a companion asking a question and
     * waiting for the answer, and WAL means they do not contend with the writer. Returns
     * an empty list rather than throwing: a dashboard panel with no rows is a smaller
     * failure than a dashboard with no page.
     */
    public <T> List<T> query(String what, String sql, Function<ResultSet, T> mapper, Object... parameters) {
        if (connection == null) {
            return List.of();
        }
        List<T> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, java.util.Arrays.asList(parameters));
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    rows.add(mapper.apply(results));
                }
            }
        } catch (SQLException | RuntimeException e) {
            LOG.warn("Storage read failed ({}): {}", what, e.toString());
        }
        return rows;
    }

    /** Runs a write on the caller's thread and waits. For migrations and shutdown only. */
    public void executeNow(String what, String sql, Object... parameters) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, java.util.Arrays.asList(parameters));
            statement.executeUpdate();
        } catch (SQLException e) {
            LOG.warn("Storage write failed ({}): {}", what, e.toString());
        }
    }

    private void drain() {
        while (running.get() || !queue.isEmpty()) {
            try {
                Write write = queue.poll(500, TimeUnit.MILLISECONDS);
                if (write == null) {
                    continue;
                }
                apply(write);
                // Anything else already waiting goes in the same transaction. Under load
                // that is the difference between one commit per player and one per burst.
                List<Write> batch = new ArrayList<>();
                queue.drainTo(batch, 256);
                if (!batch.isEmpty()) {
                    inTransaction(batch);
                }
            } catch (InterruptedException stopping) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                LOG.warn("Storage writer recovered from {}", e.toString());
            }
        }
    }

    private void inTransaction(List<Write> batch) {
        try {
            connection.setAutoCommit(false);
            for (Write write : batch) {
                apply(write);
            }
            connection.commit();
        } catch (SQLException e) {
            LOG.warn("Storage batch failed: {}", e.toString());
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // Nothing useful to do; the next batch will fail visibly if it matters.
            }
        }
    }

    private void apply(Write write) {
        try (PreparedStatement statement = connection.prepareStatement(write.sql())) {
            bind(statement, write.parameters());
            statement.executeUpdate();
            written.incrementAndGet();
        } catch (SQLException e) {
            LOG.warn("Storage write failed ({}): {}", write.what(), e.toString());
        }
    }

    private static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) {
            statement.setObject(i + 1, parameters.get(i));
        }
    }

    /** Stops accepting writes, finishes what is queued, and closes the file. */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (writer != null) {
            try {
                // Long enough to flush a normal backlog, short enough that a wedged disk
                // cannot hold up the proxy's shutdown.
                writer.join(3000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException e) {
            LOG.warn("Storage did not close cleanly: {}", e.toString());
        }
        if (dropped.get() > 0) {
            LOG.info("Storage wrote {} record(s), dropped {}", written.get(), dropped.get());
        }
    }
}
