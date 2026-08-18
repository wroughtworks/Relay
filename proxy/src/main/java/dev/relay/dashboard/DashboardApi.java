package dev.relay.dashboard;

import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import dev.relay.proxy.ServerGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * The proxy's live state, shaped for a browser.
 *
 * <p>Read-only on purpose, and the boundary is the point rather than a stage to grow out
 * of. Nothing here can move a player, close a connection or touch config, so no request
 * this serves can destabilise a running network &mdash; which is what makes it reasonable
 * to expose before any of spec &sect;9.6's authentication exists.
 *
 * <p>Records rather than the live objects. Serialising {@link ConnectedPlayer} directly
 * would reach a Netty channel, a session handler and every packet queue behind it; these
 * are flat, immutable, and say exactly what leaves the process.
 *
 * <h2>What is deliberately absent</h2>
 * Player IP addresses. The proxy knows them and an operator has fair use for them, but
 * this API has no authentication yet, and an unauthenticated endpoint that pairs usernames
 * with addresses is a different class of leak from one that says who is online. They can
 * be added with the login that guards them.
 */
public final class DashboardApi {

    private final RelayProxy proxy;

    public DashboardApi(RelayProxy proxy) {
        this.proxy = proxy;
    }

    /** Enough for a landing page, in one request rather than three. */
    public record Overview(String version, long uptimeSeconds, int players, int maxPlayers,
                           int servers, int groups, String balance, String bind) {
    }

    public record ServerView(String name, String address, int players, String group) {
    }

    public record GroupView(String name, List<String> members, int players) {
    }

    /**
     * @param server  where they are now, or {@code null} mid-switch
     * @param onlineSeconds how long since the connection opened, not since they joined
     *                      the server named above
     */
    public record PlayerView(String username, String uuid, String server, int protocol,
                             long onlineSeconds) {
    }

    public Overview overview() {
        return new Overview(
                RelayProxy.version(),
                proxy.uptimeMillis() / 1000,
                proxy.players().count(),
                proxy.config().maxPlayers(),
                proxy.servers().size(),
                proxy.groups().size(),
                proxy.config().balance().configName(),
                proxy.config().bind().getHostString() + ":" + proxy.config().bind().getPort());
    }

    public List<ServerView> servers() {
        List<ServerView> views = new ArrayList<>();
        for (RegisteredServer server : proxy.servers()) {
            views.add(new ServerView(
                    server.name(),
                    server.address().getHostString() + ":" + server.address().getPort(),
                    server.playerCount(),
                    groupOf(server)));
        }
        return views;
    }

    public List<GroupView> groups() {
        List<GroupView> views = new ArrayList<>();
        for (ServerGroup group : proxy.groups()) {
            views.add(new GroupView(group.name(),
                    group.members().stream().map(RegisteredServer::name).toList(),
                    group.playerCount()));
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
        return new PlayerView(
                player.username(),
                player.uuid().toString(),
                current == null ? null : current.target().name(),
                player.version().id(),
                (System.currentTimeMillis() - player.connectedAt()) / 1000);
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
