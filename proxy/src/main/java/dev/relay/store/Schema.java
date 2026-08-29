package dev.relay.store;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * The tables, and how a file made by an older Relay becomes one this build understands.
 *
 * <p>Migrations are an append-only list, applied in order, tracked by SQLite's own
 * {@code user_version}. No framework, no checksum table, no separate files: this is a
 * handful of tables in a single-node database, and a migration tool would be more code
 * than the thing it migrates.
 *
 * <p>The rule for changing this: <b>add a step, never edit one</b>. An edited step has
 * already run on somebody's file and will not run again, so the schema it produces exists
 * only on machines that have not upgraded yet -- which is the kind of bug that appears
 * months later on one server and nowhere else.
 */
final class Schema {

    private Schema() {
    }

    /**
     * Applied in order. Index is the version, so the first entry takes a file from 0 to 1.
     *
     * <p>Times are epoch milliseconds throughout, and never local time. A history table
     * that stores wall-clock strings cannot be read across a daylight-saving boundary
     * without knowing which zone wrote it.
     */
    private static final List<String[]> STEPS = List.<String[]>of(
            new String[] {
                // A player's whole visit to the network: one row from connect to
                // disconnect, whatever they did in between.
                """
                CREATE TABLE player_session (
                    id            INTEGER PRIMARY KEY AUTOINCREMENT,
                    route_id      TEXT    NOT NULL,
                    uuid          TEXT    NOT NULL,
                    username      TEXT    NOT NULL,
                    protocol      INTEGER NOT NULL,
                    virtual_host  TEXT,
                    connected_at  INTEGER NOT NULL,
                    disconnected_at INTEGER
                )
                """,
                "CREATE INDEX idx_session_uuid ON player_session(uuid, connected_at DESC)",
                "CREATE INDEX idx_session_time ON player_session(connected_at DESC)",
                // Deliberately unique: route_id is the session key everything else joins
                // on, and two sessions sharing one would silently merge two players'
                // histories rather than failing.
                "CREATE UNIQUE INDEX idx_session_route ON player_session(route_id)",

                // Where they were, and when. One row per backend they were on, which is
                // what turns "Carson was here" into "Carson went lobby, survival-01,
                // lobby" -- the connection history spec 9.1 asks for.
                """
                CREATE TABLE player_visit (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id  INTEGER NOT NULL REFERENCES player_session(id) ON DELETE CASCADE,
                    server      TEXT    NOT NULL,
                    joined_at   INTEGER NOT NULL,
                    left_at     INTEGER
                )
                """,
                "CREATE INDEX idx_visit_session ON player_visit(session_id, joined_at)",
                "CREATE INDEX idx_visit_server ON player_visit(server, joined_at DESC)",

                // A backend crossing between usable and not. Small, and the thing an
                // operator wants when asked "was it down last night?".
                """
                CREATE TABLE server_health (
                    id       INTEGER PRIMARY KEY AUTOINCREMENT,
                    server   TEXT    NOT NULL,
                    at       INTEGER NOT NULL,
                    previous TEXT    NOT NULL,
                    current  TEXT    NOT NULL,
                    detail   TEXT
                )
                """,
                "CREATE INDEX idx_health_time ON server_health(at DESC)",

                // The same samples the in-memory ring holds, kept past its hour.
                """
                CREATE TABLE metric_sample (
                    at          INTEGER PRIMARY KEY,
                    players     INTEGER NOT NULL,
                    connections INTEGER NOT NULL,
                    bytes_in    INTEGER NOT NULL,
                    bytes_out   INTEGER NOT NULL,
                    connects    REAL    NOT NULL,
                    switches    REAL    NOT NULL
                )
                """,

                // Who did what. Empty until the dashboard can do anything: today every
                // write path in this project is a player's own action or the proxy's
                // own decision. The table exists now so that the first admin action has
                // somewhere to be recorded rather than a schema change to wait for.
                """
                CREATE TABLE audit_event (
                    id      INTEGER PRIMARY KEY AUTOINCREMENT,
                    at      INTEGER NOT NULL,
                    actor   TEXT    NOT NULL,
                    action  TEXT    NOT NULL,
                    target  TEXT,
                    detail  TEXT
                )
                """,
                "CREATE INDEX idx_audit_time ON audit_event(at DESC)",
            });

    static int version(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery("PRAGMA user_version")) {
            return results.next() ? results.getInt(1) : 0;
        }
    }

    static void migrate(Connection connection) throws SQLException {
        int current = version(connection);
        for (int step = current; step < STEPS.size(); step++) {
            // Each migration is one transaction. A step that fails half way through would
            // otherwise leave a file that is neither version, which is the worst of the
            // three states to be in.
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (String sql : STEPS.get(step)) {
                    statement.execute(sql);
                }
                statement.execute("PRAGMA user_version = " + (step + 1));
                connection.commit();
            } catch (SQLException failed) {
                connection.rollback();
                throw failed;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    static int latest() {
        return STEPS.size();
    }
}
