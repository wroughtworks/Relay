package dev.relay.metrics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;

/**
 * The counters of spec &sect;10, and enough history to draw them.
 *
 * <p>Two different things live here on purpose. The <b>counters</b> only ever go up, and
 * answer "how much since this proxy started" -- which is the question a total is good at
 * and the only question it can answer honestly. The <b>history</b> is a fixed ring of
 * samples taken on a timer, each one holding the <em>rate</em> since the previous sample,
 * because "42 million bytes" tells an operator nothing and "1.2 MB/s, climbing" tells them
 * everything.
 *
 * <p>Rates are computed at sample time rather than in the browser. A client that has to
 * difference two totals will get it wrong the first time the proxy restarts and the totals
 * go backwards, and every client would have to get it right separately.
 *
 * <h2>What is not counted, and why</h2>
 * Packets. Spec &sect;10 lists {@code relay_packets_received} and {@code relay_packets_sent},
 * and counting them accurately means counting <em>frames</em>, which happens inside the
 * codec rather than at the head of the pipeline. At the head, outbound writes happen to be
 * one frame each but inbound reads are TCP chunks, so the two numbers would not mean the
 * same thing -- and a pair of metrics that look comparable and are not is worse than an
 * absent pair. Bytes are counted at the head, where they are unambiguously wire bytes.
 *
 * <p>All counters are safe to touch from any thread. {@link LongAdder} rather than
 * {@code AtomicLong} because these are written from every event loop at once and read
 * rarely, which is exactly the trade it makes.
 */
public final class Metrics {

    /** How often a sample is taken. Also the resolution of every rate below. */
    private static final long SAMPLE_MILLIS = 5_000;

    /**
     * How many samples are kept: an hour at five seconds apiece.
     *
     * <p>An hour is the window in which "when did this start?" is still a useful question.
     * Beyond that the answer belongs in storage rather than in a ring buffer, and 720
     * samples is small enough that keeping them costs nothing worth measuring.
     */
    private static final int HISTORY = 720;

    private final LongAdder connectionsTotal = new LongAdder();
    private final LongAdder connectionsFailed = new LongAdder();
    private final LongAdder switches = new LongAdder();
    private final LongAdder failovers = new LongAdder();
    private final LongAdder routeDecisions = new LongAdder();
    private final LongAdder routeFailures = new LongAdder();
    private final LongAdder playerBytesIn = new LongAdder();
    private final LongAdder playerBytesOut = new LongAdder();
    private final LongAdder backendBytesIn = new LongAdder();
    private final LongAdder backendBytesOut = new LongAdder();
    private final AtomicInteger connectionsActive = new AtomicInteger();

    private final Deque<Sample> history = new ArrayDeque<>(HISTORY);
    private final IntSupplier playerCount;

    /** Where samples are kept once they fall out of the ring. Null when storage is off. */
    private dev.relay.store.Database database;

    private ScheduledExecutorService sampler;
    private long lastAt;
    private long lastConnections;
    private long lastSwitches;
    private long lastBytesIn;
    private long lastBytesOut;

    /**
     * @param playerCount read at each sample. A supplier rather than a number because the
     *                    registry is the truth and copying it here would create a second
     *                    one that drifts.
     */
    public Metrics(IntSupplier playerCount) {
        this.playerCount = playerCount;
    }

    /**
     * One moment, with rates rather than totals.
     *
     * @param at         epoch millis
     * @param bytesIn    player-side bytes per second received in the interval ending here
     * @param bytesOut   player-side bytes per second sent
     * @param connects   new connections per second
     * @param switches   server switches per minute, which is a rate slow enough that
     *                   per-second would read as a column of zeroes
     */
    public record Sample(long at, int players, int connections,
                         long bytesIn, long bytesOut,
                         double connects, double switches) {
    }

    /** Everything a dashboard needs in one answer, since it asks for all of it at once. */
    public record Snapshot(long connectionsTotal, long connectionsFailed, int connectionsActive,
                           long switches, long failovers,
                           long routeDecisions, long routeFailures,
                           long playerBytesIn, long playerBytesOut,
                           long backendBytesIn, long backendBytesOut,
                           long sampleMillis, List<Sample> history) {
    }

    // ------------------------------------------------------------------ counting

    /** A player connection opened. Failed handshakes still count: they were connections. */
    public void connectionOpened() {
        connectionsTotal.increment();
        connectionsActive.incrementAndGet();
    }

    public void connectionClosed() {
        connectionsActive.decrementAndGet();
    }

    /** A connection that never became a session -- refused, timed out, or malformed. */
    public void connectionFailed() {
        connectionsFailed.increment();
    }

    public void switched() {
        switches.increment();
    }

    /** A player moved because where they were went away, rather than because they asked. */
    public void failover() {
        failovers.increment();
    }

    /**
     * A routing decision was started: a join, or a fallback after a backend was lost.
     *
     * <p>Not a player asking to move. A requested switch has a destination already and
     * is counted by {@link #switched()}; what this counts is Relay choosing, which is
     * the thing that can run out of options. The two together are all the routing work
     * the proxy does.
     *
     * <p>Counted separately from failures rather than as a success/failure pair, because
     * an attempt and its outcome happen at different moments and on different threads.
     * The ratio is the interesting number either way -- failures alone cannot tell a
     * broken network from a quiet one.
     */
    public void routeAttempted() {
        routeDecisions.increment();
    }

    /** A routing decision that ran out of candidates, ending the player's session. */
    public void routeFailed() {
        routeFailures.increment();
    }

    /**
     * Wire bytes, kept apart by which side of the proxy they crossed.
     *
     * <p>Merging them was the first version and it was useless: almost every byte a
     * backend sends is forwarded to a player, so "in" and "out" came out equal to
     * within a rounding error and the two charts drew the same line twice. Split, they
     * answer different questions -- the player side is what an uplink is billed for,
     * the backend side is internal and usually free.
     *
     * @param playerSide true for a channel facing a player, false for one facing a backend
     * @param inbound    true for bytes Relay received on it
     */
    public void wireBytes(boolean playerSide, boolean inbound, int count) {
        if (playerSide) {
            (inbound ? playerBytesIn : playerBytesOut).add(count);
        } else {
            (inbound ? backendBytesIn : backendBytesOut).add(count);
        }
    }

    // ------------------------------------------------------------------ sampling

    /**
     * Also write each sample to storage, so charts outlive a restart.
     *
     * <p>Optional on purpose. The ring above is what the dashboard draws from moment to
     * moment and needs nothing on disk; persistence only extends how far back the
     * question can be asked. Losing it costs history, not the feature.
     */
    public void persistTo(dev.relay.store.Database database) {
        this.database = database;
    }

    /** Samples from before this proxy started, oldest first. */
    public List<Sample> since(long from) {
        if (database == null) {
            return List.of();
        }
        return database.query("metric history",
                "SELECT at, players, connections, bytes_in, bytes_out, connects, switches "
                        + "FROM metric_sample WHERE at >= ? ORDER BY at",
                results -> {
                    try {
                        return new Sample(results.getLong("at"), results.getInt("players"),
                                results.getInt("connections"), results.getLong("bytes_in"),
                                results.getLong("bytes_out"), results.getDouble("connects"),
                                results.getDouble("switches"));
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }, from);
    }

    public void start() {
        lastAt = System.currentTimeMillis();
        sampler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "relay-metrics");
            thread.setDaemon(true);
            return thread;
        });
        // Fixed delay, and the body catches everything: scheduleWithFixedDelay silently
        // stops repeating a task that throws, and a metrics thread that quietly died would
        // show up as a chart that simply stopped, which is indistinguishable from an idle
        // network.
        sampler.scheduleWithFixedDelay(this::sampleQuietly, SAMPLE_MILLIS, SAMPLE_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (sampler != null) {
            sampler.shutdownNow();
            sampler = null;
        }
    }

    private void sampleQuietly() {
        try {
            sample();
        } catch (RuntimeException ignored) {
            // A missing sample is a gap in a chart. A thrown one is no chart at all.
        }
    }

    private void sample() {
        long now = System.currentTimeMillis();
        long elapsed = Math.max(1, now - lastAt);

        long connections = connectionsTotal.sum();
        long switchCount = switches.sum();
        long in = playerBytesIn.sum();
        long out = playerBytesOut.sum();

        Sample sample = new Sample(now,
                playerCount.getAsInt(),
                connectionsActive.get(),
                perSecond(in - lastBytesIn, elapsed),
                perSecond(out - lastBytesOut, elapsed),
                (connections - lastConnections) * 1000.0 / elapsed,
                (switchCount - lastSwitches) * 60_000.0 / elapsed);

        lastAt = now;
        lastConnections = connections;
        lastSwitches = switchCount;
        lastBytesIn = in;
        lastBytesOut = out;

        synchronized (history) {
            if (history.size() == HISTORY) {
                history.removeFirst();
            }
            history.addLast(sample);
        }

        if (database != null) {
            // INSERT OR REPLACE, because `at` is the primary key and two samples in the
            // same millisecond would otherwise fail the whole batch they were in.
            database.submit("metric sample",
                    "INSERT OR REPLACE INTO metric_sample"
                            + "(at, players, connections, bytes_in, bytes_out, connects, switches) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    sample.at(), sample.players(), sample.connections(),
                    sample.bytesIn(), sample.bytesOut(), sample.connects(), sample.switches());
        }
    }

    private static long perSecond(long delta, long elapsedMillis) {
        return Math.max(0, delta) * 1000 / elapsedMillis;
    }

    public Snapshot snapshot() {
        List<Sample> samples;
        synchronized (history) {
            samples = List.copyOf(history);
        }
        return new Snapshot(connectionsTotal.sum(), connectionsFailed.sum(), connectionsActive.get(),
                switches.sum(), failovers.sum(), routeDecisions.sum(), routeFailures.sum(),
                playerBytesIn.sum(), playerBytesOut.sum(),
                backendBytesIn.sum(), backendBytesOut.sum(),
                SAMPLE_MILLIS, samples);
    }
}
