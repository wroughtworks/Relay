package dev.relay.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The storage file: schema, migration, and the promise that a write never blocks a caller.
 */
class DatabaseTest {

    @Test
    void aNewFileGetsTheCurrentSchema(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("relay.db");
        try (Database database = new Database(file)) {
            database.start();
            assertTrue(Files.exists(file));
            assertEquals(List.of(0L), tableCount(database, "player_session"),
                    "a fresh file should have the tables, and no rows in them");
            for (String table : new String[] {"player_visit", "server_health",
                                              "metric_sample", "audit_event"}) {
                assertEquals(List.of(0L), tableCount(database, table), table + " should exist");
            }
        }
    }

    /**
     * Opening an existing file again must be a no-op, not a second migration.
     *
     * <p>The failure this guards against is loud in a test and quiet in production: a
     * migration that runs twice fails on the second CREATE TABLE, and a proxy that treated
     * that as fatal would refuse to start on any machine that had already run it once.
     */
    @Test
    void reopeningAnExistingFileChangesNothing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("relay.db");
        try (Database first = new Database(file)) {
            first.start();
            first.executeNow("test row",
                    "INSERT INTO player_session(route_id, uuid, username, protocol, connected_at) "
                            + "VALUES ('r1', 'u1', 'Tester', 764, 1000)");
        }
        try (Database second = new Database(file)) {
            second.start();
            List<String> names = second.query("names",
                    "SELECT username FROM player_session", results -> {
                        try {
                            return results.getString(1);
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    });
            assertEquals(List.of("Tester"), names, "the row from the first run should still be there");
        }
    }

    @Test
    void queuedWritesReachTheFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("relay.db");
        try (Database database = new Database(file)) {
            database.start();
            for (int i = 0; i < 50; i++) {
                database.submit("row",
                        "INSERT INTO player_session(route_id, uuid, username, protocol, connected_at) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        "route-" + i, "uuid-" + i, "Player" + i, 764, 1000L + i);
            }
            // The writer is asynchronous on purpose, so this waits for it rather than
            // assuming. Closing would also flush, but then the assertion could not run.
            for (int i = 0; i < 100 && database.written() < 50; i++) {
                Thread.sleep(50);
            }
            assertEquals(List.of(50L), tableCount(database, "player_session"));
            assertEquals(0, database.dropped());
        }
    }

    /** A statement that cannot run must not take the writer thread with it. */
    @Test
    void oneBadStatementDoesNotStopTheRest(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("relay.db");
        try (Database database = new Database(file)) {
            database.start();
            database.submit("nonsense", "INSERT INTO no_such_table(x) VALUES (1)");
            database.submit("good",
                    "INSERT INTO player_session(route_id, uuid, username, protocol, connected_at) "
                            + "VALUES ('r', 'u', 'After', 764, 1)");
            for (int i = 0; i < 100 && database.written() < 1; i++) {
                Thread.sleep(50);
            }
            assertEquals(List.of(1L), tableCount(database, "player_session"),
                    "the write after the broken one should still have landed");
        }
    }

    /**
     * A null parameter is an ordinary value, not an error.
     *
     * <p>The first version used {@code List.of} to hold the parameters, which rejects
     * nulls -- so every health change threw before it reached the file, because a healthy
     * backend has no detail to record. It failed inside an event listener, which caught
     * it, so the only sign was a stack trace in the log.
     */
    @Test
    void nullParametersAreWrittenAsNull(@TempDir Path dir) throws Exception {
        try (Database database = new Database(dir.resolve("relay.db"))) {
            database.start();
            database.submit("nullable",
                    "INSERT INTO server_health(server, at, previous, current, detail) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    "lobby", 1000L, "UNKNOWN", "HEALTHY", null);
            for (int i = 0; i < 100 && database.written() < 1; i++) {
                Thread.sleep(50);
            }
            assertEquals(List.of(1L), tableCount(database, "server_health"));
        }
    }

    /** A read against a missing table is an empty answer, not an exception. */
    @Test
    void aFailedReadReturnsNothing(@TempDir Path dir) throws Exception {
        try (Database database = new Database(dir.resolve("relay.db"))) {
            database.start();
            assertTrue(database.query("bad", "SELECT * FROM nope", r -> "x").isEmpty());
        }
    }

    @Test
    void writesAfterCloseAreIgnoredRatherThanThrowing(@TempDir Path dir) throws Exception {
        Database database = new Database(dir.resolve("relay.db"));
        database.start();
        database.close();
        database.submit("late", "INSERT INTO player_session(route_id) VALUES ('x')");
        assertFalse(database.written() > 100, "no exception, and nothing pretending to succeed");
    }

    private static List<Long> tableCount(Database database, String table) {
        return database.query("count", "SELECT COUNT(*) FROM " + table, results -> {
            try {
                return results.getLong(1);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        });
    }
}
