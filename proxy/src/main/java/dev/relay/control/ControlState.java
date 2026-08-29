package dev.relay.control;

import dev.relay.health.BackendHealth;
import dev.relay.health.BackendStats;
import dev.relay.metrics.Metrics;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.NetworkRoute;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import dev.relay.proxy.ServerGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * The proxy's live state, flattened into things that can cross a process boundary.
 *
 * <p>Companions run in their own JVMs and cannot hold a {@link ConnectedPlayer}: doing so
 * would reach a Netty channel, a session handler and every packet queue behind it. These
 * records are flat, immutable, and say exactly what leaves the process &mdash; which is
 * the whole contract between Relay and anything watching it.
 *
 * <p>Read-only, and the boundary is the point rather than a stage to grow out of. Nothing
 * here can move a player, close a connection or touch config, so nothing a companion asks
 * for can destabilise a running network.
 *
 * <h2>What is deliberately absent</h2>
 * Player IP addresses. The proxy knows them and an operator has fair use for them, but
 * this API has no authentication yet, and an unauthenticated endpoint that pairs usernames
 * with addresses is a different class of leak from one that says who is online. They can
 * be added with the login that guards them.
 */
public final class ControlState {

    private final RelayProxy proxy;

    public ControlState(RelayProxy proxy) {
        this.proxy = proxy;
    }

    /** Enough for a landing page, in one request rather than three. */
    public record Overview(String node, String version, long uptimeSeconds, int players,
                           int maxPlayers, int servers, int serversUp, int groups,
                           String balance, String bind) {
    }

    /**
     * @param status         Relay's health verdict for this backend
     * @param latencyMillis  last successful status ping, or -1
     * @param reportedPlayers what the backend says is online. A number above
     *                        {@code players} means people are reaching it without passing
     *                        through this proxy, which is worth seeing side by side
     * @param detail         why, when the status is not healthy
     */
    public record ServerView(String name, String address, int players, String group,
                             String status, long latencyMillis, int reportedPlayers,
                             String version, String detail, Load load) {
    }

    /**
     * A backend's own view of its load, or null if it has never reported.
     *
     * <p>Separate from {@link ServerView} rather than flattened into it, because these
     * fields go stale together and are only meaningful together. A client that has to
     * decide whether to trust six loose fields will get it wrong; one nullable object
     * with an {@code ageSeconds} on it cannot be misread.
     *
     * @param stale      true when nothing has arrived recently. A backend can only report
     *                   while a player is on it, so this usually means empty rather than
     *                   broken -- but it might mean frozen, and the age says which
     * @param memoryUsedMb heap in use, in whole megabytes, since bytes on a dashboard are
     *                     a number nobody reads
     */
    public record Load(double tps1m, double tps5m, double tps15m, double msptMean,
                       long memoryUsedMb, long memoryMaxMb, double cpuLoad,
                       long uptimeSeconds, boolean stale, long ageSeconds) {
    }

    /**
     * @param healthy how many members are taking new players, which is the number that
     *                decides whether a group is one bad night from having nowhere to send
     *                anyone
     */
    public record GroupView(String name, List<String> members, int players, int healthy) {
    }

    /**
     * @param server  where they are now, or {@code null} mid-switch
     * @param onlineSeconds how long since the connection opened, not since they joined
     *                      the server named above
     */
    /**
     * @param routeId     spec 7.7's per-session id, for tying log lines to a dashboard row
     * @param virtualHost the hostname they connected to, which with forced hosts is the
     *                    reason they landed where they did
     * @param route       spec 9.1's complete route, hop by hop
     */
    public record PlayerView(String username, String uuid, String server, int protocol,
                             long onlineSeconds, String routeId, String virtualHost,
                             List<HopView> route) {
    }

    /** One hop, flattened. Kind as a string so a client can style without parsing names. */
    public record HopView(String kind, String name, String detail) {
    }

    public Overview overview() {
        return new Overview(
                proxy.config().nodeName(),
                RelayProxy.version(),
                proxy.uptimeMillis() / 1000,
                proxy.players().count(),
                proxy.config().maxPlayers(),
                proxy.servers().size(),
                (int) proxy.servers().stream().filter(RegisteredServer::acceptsNewPlayers).count(),
                proxy.groups().size(),
                proxy.config().balance().configName(),
                proxy.config().bind().getHostString() + ":" + proxy.config().bind().getPort());
    }

    /**
     * Spec 10's counters, straight through.
     *
     * <p>Not reshaped on the way out. The snapshot is already the flat, immutable thing
     * this class exists to produce, and a second shape would be one more place for the
     * meaning of "bytes" to drift.
     */
    public Metrics.Snapshot metrics() {
        return proxy.metrics().snapshot();
    }

    public List<ServerView> servers() {
        List<ServerView> views = new ArrayList<>();
        for (RegisteredServer server : proxy.servers()) {
            BackendHealth health = server.health();
            views.add(new ServerView(
                    server.name(),
                    server.address().getHostString() + ":" + server.address().getPort(),
                    server.playerCount(),
                    groupOf(server),
                    health.state().name(),
                    health.latencyMillis(),
                    health.reportedPlayers(),
                    health.version(),
                    health.detail(),
                    load(server.stats())));
        }
        return views;
    }

    public List<GroupView> groups() {
        List<GroupView> views = new ArrayList<>();
        for (ServerGroup group : proxy.groups()) {
            views.add(new GroupView(group.name(),
                    group.members().stream().map(RegisteredServer::name).toList(),
                    group.playerCount(),
                    (int) group.members().stream().filter(RegisteredServer::acceptsNewPlayers).count()));
        }
        return views;
    }

    public List<PlayerView> players() {
        List<PlayerView> views = new ArrayList<>();
        for (ConnectedPlayer player : proxy.players().snapshot()) {
            views.add(view(player));
        }
        return views;
    }

    public PlayerView view(ConnectedPlayer player) {
        ServerConnection current = player.connectedServer();
        NetworkRoute route = NetworkRoute.of(player, proxy);
        return new PlayerView(
                player.username(),
                player.uuid().toString(),
                current == null ? null : current.target().name(),
                player.version().id(),
                (System.currentTimeMillis() - player.connectedAt()) / 1000,
                route.routeId(),
                route.virtualHost(),
                route.hops().stream()
                        .map(hop -> new HopView(hop.kind().name(), hop.name(), hop.detail()))
                        .toList());
    }

    private static Load load(BackendStats stats) {
        if (stats == null) {
            return null;
        }
        return new Load(stats.tps1m(), stats.tps5m(), stats.tps15m(), stats.msptMean(),
                stats.usedMemory() < 0 ? -1 : stats.usedMemory() / (1024 * 1024),
                stats.maxMemory() < 0 ? -1 : stats.maxMemory() / (1024 * 1024),
                stats.cpuLoad(), stats.uptimeSeconds(), !stats.isFresh(), stats.ageSeconds());
    }

    /** @return the group this backend belongs to, or {@code null} if it is on its own */
    private String groupOf(RegisteredServer server) {
        for (ServerGroup group : proxy.groups()) {
            if (group.members().contains(server)) {
                return group.name();
            }
        }
        return null;
    }
}
