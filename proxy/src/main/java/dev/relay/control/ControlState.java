package dev.relay.control;

import dev.relay.health.BackendHealth;
import dev.relay.health.BackendStats;
import dev.relay.metrics.Metrics;
import dev.relay.store.History;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.NetworkRoute;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import dev.relay.proxy.ServerGroup;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

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

    /**
     * One page of the player list, with the two counts needed to make sense of it.
     *
     * <p>A page rather than a list because the whole list stops being a sensible answer
     * somewhere in the low thousands. Every {@link PlayerView} carries a freshly walked
     * route, and a hundred thousand of them is several hundred megabytes of objects and
     * JSON built, sent and thrown away every few seconds &mdash; on the proxy's own event
     * loops, to render a table nobody can read.
     *
     * @param total   everyone online, so a filtered page can say what it is a slice of
     * @param matched how many passed the filter, which is not {@code players.size()} and
     *                is the number paging controls need
     * @param edges   upstream proxies seen across <em>everyone</em>, not just this page.
     *                The topology map used to derive these by scanning the full player
     *                list; it cannot once the list is a page, and the aggregate is cheap
     *                to take here where every player is already being looked at
     */
    public record PlayerPage(int total, int matched, int offset, int limit,
                             List<PlayerView> players, List<EdgeView> edges) {
    }

    /** An upstream proxy, known only because players arrived through it. */
    public record EdgeView(String name, int players) {
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

    /**
     * Recent sessions from storage, newest first.
     *
     * <p>Empty rather than an error when storage is off, for the same reason the other
     * collections are: a companion that has to handle both "nothing recorded" and "an
     * error object where a list belongs" will get one of them wrong.
     */
    public List<History.Session> history(String uuid, int limit) {
        History history = proxy.history();
        return history == null ? List.of() : history.sessions(uuid, limit);
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

    /** More rows than this in one answer is a client bug, not a request worth serving. */
    public static final int MAX_LIMIT = 500;

    /**
     * Alphabetical, because a page of an unordered collection is not a page at all: the
     * next request would re-order the rows underneath the offset, showing some players
     * twice and skipping others. Names are unique, so this is a total order.
     */
    private static final Comparator<ConnectedPlayer> ORDER =
            Comparator.comparing(ConnectedPlayer::username, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(ConnectedPlayer::uuid);

    /**
     * One page of the people online, narrowed and ordered.
     *
     * <p>Cost is the point. This walks everyone exactly once and holds only the page:
     * the filter is applied to the live {@link ConnectedPlayer} before any view exists,
     * so a route is walked and a {@link PlayerView} allocated only for rows that are
     * actually going to be sent. A partial selection keeps the best {@code offset+limit}
     * as it goes rather than sorting the whole network, so a hundred-thousand-player node
     * answers a fifty-row page in one pass and a few kilobytes.
     *
     * <p>Deep offsets degrade honestly rather than being refused: the selection grows to
     * whatever was asked for, capped by how many players there are, so the worst case is
     * the full sort this exists to avoid and never worse.
     *
     * @param query  free text against username, route id, backend and virtual host; the
     *               same match the dashboard used to do in the browser, moved to where
     *               the data is
     * @param server a backend or group name. Unknown names match nobody, which is the
     *               honest answer to "who is on a server that does not exist"
     */
    public PlayerPage players(String query, String server, int offset, int limit) {
        int wanted = Math.max(0, Math.min(limit, MAX_LIMIT));
        int from = Math.max(0, offset);
        int total = proxy.players().count();
        // Anything past the last player is an empty page whatever the offset, so the
        // selection never needs to be bigger than the network.
        int keep = (int) Math.min((long) from + wanted, total);

        String needle = query == null || query.isBlank() ? null : query.trim();
        Set<RegisteredServer> targets = targets(server);

        // Largest first, so the head is the row to drop once the selection is full.
        PriorityQueue<ConnectedPlayer> best = new PriorityQueue<>(ORDER.reversed());
        Map<String, Integer> edges = new LinkedHashMap<>();
        int matched = 0;

        for (ConnectedPlayer player : proxy.players().all()) {
            InetSocketAddress upstream = player.proxiedFrom();
            if (upstream != null) {
                edges.merge(upstream.getHostString(), 1, Integer::sum);
            }
            if (!matches(player, needle, targets)) {
                continue;
            }
            matched++;
            if (keep == 0) {
                continue;
            }
            // Compare against the worst row held before touching the heap. Once the page
            // is full most players cannot reach it, and asking is one comparison where
            // adding and evicting is two O(log k) walks -- which is the difference
            // between paging costing a scan and paging costing a sort.
            if (best.size() < keep) {
                best.add(player);
            } else if (ORDER.compare(player, best.peek()) < 0) {
                best.poll();
                best.add(player);
            }
        }

        List<ConnectedPlayer> selected = new ArrayList<>(best);
        selected.sort(ORDER);
        List<PlayerView> views = new ArrayList<>(Math.min(wanted, selected.size()));
        for (int i = from; i < selected.size(); i++) {
            views.add(view(selected.get(i)));
        }

        List<EdgeView> upstreams = new ArrayList<>(edges.size());
        edges.forEach((name, count) -> upstreams.add(new EdgeView(name, count)));
        return new PlayerPage(total, matched, from, wanted, views, upstreams);
    }

    /**
     * @param needle  already trimmed, or null for "everyone"
     * @param targets null for "any backend"; empty for "a backend nobody is on"
     */
    private static boolean matches(ConnectedPlayer player, String needle,
                                   Set<RegisteredServer> targets) {
        ServerConnection current = player.connectedServer();
        if (targets != null && (current == null || !targets.contains(current.target()))) {
            return false;
        }
        return needle == null
                || contains(player.username(), needle)
                || contains(player.routeId(), needle)
                || contains(player.virtualHost(), needle)
                || (current != null && contains(current.target().name(), needle));
    }

    /**
     * Case-insensitive substring.
     *
     * <p>Written out rather than {@code toLowerCase().contains()} because this runs four
     * times per player per request, and the obvious version would allocate four throwaway
     * strings for every person online to answer one search.
     */
    private static boolean contains(String value, String needle) {
        if (value == null) {
            return false;
        }
        int last = value.length() - needle.length();
        for (int i = 0; i <= last; i++) {
            if (value.regionMatches(true, i, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves a filter name to the backends it means.
     *
     * <p>Backends compared by identity rather than by name, so the hot loop never
     * lowercases a string. A group resolves to its members, because narrowing to "the
     * survival pool" is the question an operator actually has.
     *
     * @return null when nothing was asked for, so the caller can tell "no filter" from
     *         "a filter that matches nothing"
     */
    private Set<RegisteredServer> targets(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String wanted = name.trim();
        return proxy.group(wanted)
                .<Set<RegisteredServer>>map(group -> Set.copyOf(group.members()))
                .or(() -> proxy.server(wanted).map(Set::of))
                .orElseGet(Set::of);
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
