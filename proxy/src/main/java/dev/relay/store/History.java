package dev.relay.store;

import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.ProxyEvents;
import dev.relay.proxy.RegisteredServer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Turns the events the proxy already publishes into rows that outlive it.
 *
 * <p>Written as a listener rather than as calls sprinkled through the session handlers,
 * for the same reason the switch counter is: there are several places a player can arrive
 * or leave, there will be more, and a listener cannot fall out of step with them.
 *
 * <h2>What a session looks like on disk</h2>
 * One {@code player_session} row for the whole visit, and one {@code player_visit} row per
 * backend they were on. Reading them back gives the thing spec &sect;9.1 calls connection
 * history &mdash; not just "Carson was online", but lobby, then survival-01, then lobby
 * again, with the times.
 *
 * <p>The row id is looked up by {@code route_id}, the per-session token &sect;7.7 already
 * issues. It was created to make a session greppable in a log; it turns out to be exactly
 * the primary key this needs, so nothing new has to be invented or kept in step.
 */
public final class History {

    private final Database database;
    private final int retainDays;

    /**
     * Open visits, so a switch or a disconnect can close the right one.
     *
     * <p>Kept in memory rather than found with an {@code UPDATE ... WHERE left_at IS NULL}
     * because that query would also close a visit left open by a previous run of the
     * proxy, which is a different player's row and a silent corruption of their history.
     */
    private final Map<String, Long> openVisits = new ConcurrentHashMap<>();

    private ScheduledExecutorService pruner;

    public History(Database database, int retainDays) {
        this.database = database;
        this.retainDays = retainDays;
    }

    public ProxyEvents.Listener listener() {
        return new ProxyEvents.Listener() {
            @Override
            public void onPlayerEvent(ProxyEvents.PlayerEvent event) {
                switch (event.kind()) {
                    case PLAYER_CONNECTED -> connected(event.player(), event.to());
                    case PLAYER_SWITCHED_SERVER -> switched(event.player(), event.to());
                    case PLAYER_DISCONNECTED -> disconnected(event.player());
                    default -> { }
                }
            }

            @Override
            public void onServerEvent(ProxyEvents.ServerEvent event) {
                database.submit("health change",
                        "INSERT INTO server_health(server, at, previous, current, detail) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        event.server().name(), System.currentTimeMillis(),
                        event.before().state().name(), event.after().state().name(),
                        event.after().detail());
            }
        };
    }

    private void connected(ConnectedPlayer player, RegisteredServer server) {
        long now = System.currentTimeMillis();
        database.submit("session start",
                "INSERT OR IGNORE INTO player_session"
                        + "(route_id, uuid, username, protocol, virtual_host, connected_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                player.routeId(), player.uuid().toString(), player.username(),
                player.version().id(), player.virtualHost(), now);
        openVisit(player, server, now);
    }

    private void switched(ConnectedPlayer player, RegisteredServer server) {
        long now = System.currentTimeMillis();
        closeVisit(player, now);
        openVisit(player, server, now);
    }

    private void disconnected(ConnectedPlayer player) {
        long now = System.currentTimeMillis();
        closeVisit(player, now);
        openVisits.remove(player.routeId());
        database.submit("session end",
                "UPDATE player_session SET disconnected_at = ? WHERE route_id = ?",
                now, player.routeId());
    }

    /**
     * Opens a visit row.
     *
     * <p>The session is found by route id in a sub-select rather than by an id this class
     * holds, because the insert above is queued and may not have run yet. SQLite resolves
     * it when the statement executes, by which time it has -- the writer is a single
     * thread and these were queued in order.
     */
    private void openVisit(ConnectedPlayer player, RegisteredServer server, long at) {
        if (server == null) {
            return;
        }
        openVisits.put(player.routeId(), at);
        database.submit("visit start",
                "INSERT INTO player_visit(session_id, server, joined_at) "
                        + "SELECT id, ?, ? FROM player_session WHERE route_id = ?",
                server.name(), at, player.routeId());
    }

    private void closeVisit(ConnectedPlayer player, long at) {
        if (openVisits.remove(player.routeId()) == null) {
            return;
        }
        database.submit("visit end",
                "UPDATE player_visit SET left_at = ? "
                        + "WHERE left_at IS NULL AND session_id = "
                        + "(SELECT id FROM player_session WHERE route_id = ?)",
                at, player.routeId());
    }

    /**
     * Closes anything this run left open.
     *
     * <p>A proxy that is killed rather than stopped leaves sessions with no end time, and
     * a history full of players who apparently never left is worse than one that admits
     * the proxy stopped. Only rows this run opened are touched.
     */
    public void closeOpenSessions() {
        long now = System.currentTimeMillis();
        for (String routeId : openVisits.keySet()) {
            database.executeNow("visit end at shutdown",
                    "UPDATE player_visit SET left_at = ? WHERE left_at IS NULL AND session_id = "
                            + "(SELECT id FROM player_session WHERE route_id = ?)", now, routeId);
            database.executeNow("session end at shutdown",
                    "UPDATE player_session SET disconnected_at = ? "
                            + "WHERE disconnected_at IS NULL AND route_id = ?", now, routeId);
        }
        openVisits.clear();
    }

    // ------------------------------------------------------------------- retention

    public void startPruning() {
        pruner = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "relay-store-prune");
            thread.setDaemon(true);
            return thread;
        });
        // Hourly rather than daily: an hourly job that misses a run is an hour late, and
        // a daily one scheduled at startup runs at whatever time the proxy happened to
        // restart, which is never the quiet hour someone intended.
        pruner.scheduleWithFixedDelay(this::prune, 1, 60, TimeUnit.MINUTES);
    }

    public void stopPruning() {
        if (pruner != null) {
            pruner.shutdownNow();
            pruner = null;
        }
    }

    private void prune() {
        if (retainDays <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - retainDays * 24L * 60 * 60 * 1000;
        // Visits go with their session through ON DELETE CASCADE, so only the parent is
        // named here. Deleting them separately would leave orphans on any row the cascade
        // had already taken.
        database.submit("prune sessions",
                "DELETE FROM player_session WHERE connected_at < ? AND disconnected_at IS NOT NULL",
                cutoff);
        database.submit("prune health", "DELETE FROM server_health WHERE at < ?", cutoff);
        database.submit("prune metrics", "DELETE FROM metric_sample WHERE at < ?", cutoff);
        database.submit("prune audit", "DELETE FROM audit_event WHERE at < ?", cutoff);
    }

    // ---------------------------------------------------------------------- reading

    /** One past visit, flattened for a companion. */
    public record Visit(String routeId, String username, String uuid, String server,
                        long joinedAt, Long leftAt) {
    }

    /** One completed or ongoing session. */
    public record Session(String routeId, String username, String uuid, int protocol,
                          String virtualHost, long connectedAt, Long disconnectedAt,
                          List<Visit> visits) {
    }

    /**
     * Recent sessions, newest first.
     *
     * @param uuid  restrict to one player, or null for everyone
     * @param limit how many sessions, before their visits are fetched
     */
    public List<Session> sessions(String uuid, int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        String sql = "SELECT route_id, username, uuid, protocol, virtual_host, "
                + "connected_at, disconnected_at FROM player_session "
                + (uuid == null ? "" : "WHERE uuid = ? ")
                + "ORDER BY connected_at DESC LIMIT " + capped;

        List<Session> sessions = uuid == null
                ? database.query("sessions", sql, History::readSession)
                : database.query("sessions", sql, History::readSession, uuid);

        List<Session> withVisits = new java.util.ArrayList<>(sessions.size());
        for (Session session : sessions) {
            withVisits.add(new Session(session.routeId(), session.username(), session.uuid(),
                    session.protocol(), session.virtualHost(), session.connectedAt(),
                    session.disconnectedAt(), visitsOf(session.routeId())));
        }
        return withVisits;
    }

    private List<Visit> visitsOf(String routeId) {
        return database.query("visits",
                "SELECT v.server, v.joined_at, v.left_at, s.route_id, s.username, s.uuid "
                        + "FROM player_visit v JOIN player_session s ON s.id = v.session_id "
                        + "WHERE s.route_id = ? ORDER BY v.joined_at",
                results -> {
                    try {
                        // wasNull() reports on the column read *most recently*, so it has
                        // to be asked before anything else is read. Asked at the end of
                        // the argument list, as this was, it answers for joined_at -- and
                        // an open visit came back as having ended at the epoch.
                        long left = results.getLong("left_at");
                        Long leftAt = results.wasNull() ? null : left;
                        return new Visit(results.getString("route_id"), results.getString("username"),
                                results.getString("uuid"), results.getString("server"),
                                results.getLong("joined_at"), leftAt);
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException(e);
                    }
                }, routeId);
    }

    private static Session readSession(java.sql.ResultSet results) {
        try {
            // Read, then immediately ask whether it was null: see the note in visitsOf.
            long ended = results.getLong("disconnected_at");
            Long disconnectedAt = results.wasNull() ? null : ended;
            return new Session(results.getString("route_id"), results.getString("username"),
                    results.getString("uuid"), results.getInt("protocol"),
                    results.getString("virtual_host"), results.getLong("connected_at"),
                    disconnectedAt, List.of());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
