package dev.relay.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.relay.proxy.ProxyEvents;
import dev.relay.proxy.RelayProxy;
import io.javalin.Javalin;
import io.javalin.json.JsonMapper;
import io.javalin.websocket.WsContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The dashboard's HTTP and WebSocket server.
 *
 * <p>Phase 2 of spec &sect;10, read-only half first. Every route here answers a question
 * about live state and none of them changes anything, which is what lets it ship before
 * the authentication in &sect;9.6: the worst a request can do is read.
 *
 * <p>Runs on Jetty threads, entirely separate from the Netty event loops carrying player
 * traffic. Nothing on this side can block a connection, and a dashboard that falls over
 * takes nothing with it &mdash; {@link #start()} logs and gives up rather than aborting
 * startup, because a proxy that refuses to serve players over a broken status page has
 * its priorities backwards.
 *
 * <p>One WebSocket carries every event type, as &sect;9.4 asks. A socket per type would
 * multiply connections by the number of things worth watching, and browsers cap
 * connections per origin.
 */
public final class DashboardServer {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardServer.class);

    /** Loopback hosts, for the warning about serving player data to a network. */
    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "localhost");

    private final RelayProxy proxy;
    private final DashboardApi api;
    /**
     * Nulls are serialised rather than dropped.
     *
     * <p>Gson omits a null field by default, which would make "this player is mid-switch"
     * and "this field does not exist" the same thing on the wire. A client then has to
     * treat every field as optional; with explicit nulls the shape is fixed and a missing
     * key is a real error.
     */
    private final Gson gson = new GsonBuilder().serializeNulls().create();

    /** Live sockets. A set because a browser tab may open several, and reloads leak none. */
    private final Set<WsContext> sockets = ConcurrentHashMap.newKeySet();

    private final ProxyEvents.Listener listener = this::broadcast;

    private Javalin server;

    public DashboardServer(RelayProxy proxy) {
        this.proxy = proxy;
        this.api = new DashboardApi(proxy);
    }

    public void start() {
        InetSocketAddress bind = proxy.config().dashboardBind();
        try {
            server = Javalin.create(config -> {
                config.showJavalinBanner = false;
                config.jsonMapper(gsonMapper());
                config.jetty.defaultHost = bind.getHostString();
            });
            routes();
            server.start(bind.getHostString(), bind.getPort());
        } catch (RuntimeException e) {
            LOG.error("The dashboard could not start on {}:{}; the proxy is unaffected and continues without it",
                    bind.getHostString(), bind.getPort(), e);
            server = null;
            return;
        }

        proxy.events().addListener(listener);
        LOG.info("Dashboard   http://{}:{}", bind.getHostString(), bind.getPort());
        if (!LOOPBACK.contains(bind.getHostString().toLowerCase(java.util.Locale.ROOT))) {
            LOG.warn("The dashboard is bound to {} rather than loopback, and it has no authentication yet. "
                            + "Anyone who can reach that address can see who is online and where. Put it behind "
                            + "something that authenticates, or set dashboard.bind = \"127.0.0.1:{}\" and reach "
                            + "it over an SSH tunnel.",
                    bind.getHostString(), bind.getPort());
        }
    }

    private void routes() {
        server.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));
        server.get("/api/overview", ctx -> ctx.json(api.overview()));
        server.get("/api/servers", ctx -> ctx.json(api.servers()));
        server.get("/api/groups", ctx -> ctx.json(api.groups()));
        server.get("/api/players", ctx -> ctx.json(api.players()));

        server.ws("/api/events", ws -> {
            ws.onConnect(ctx -> {
                // No timeout. These are long-lived by design, and Jetty's default would
                // close an idle one -- which is exactly what a quiet network looks like.
                ctx.session.setIdleTimeout(java.time.Duration.ZERO);
                sockets.add(ctx);
                LOG.debug("Dashboard client connected; {} watching", sockets.size());
            });
            ws.onClose(ctx -> {
                sockets.remove(ctx);
                LOG.debug("Dashboard client disconnected; {} watching", sockets.size());
            });
            ws.onError(ctx -> sockets.remove(ctx));
        });
    }

    /**
     * Fans a proxy event out to every watcher.
     *
     * <p>Called on a Netty event loop, so this must not block: a dashboard nobody is
     * watching costs an empty-set check, and one with watchers costs a serialisation and
     * a non-blocking send. A socket that fails is dropped rather than retried, since the
     * player whose session triggered this is not waiting on anyone's browser.
     */
    private void broadcast(ProxyEvents.PlayerEvent event) {
        if (sockets.isEmpty()) {
            return;
        }
        String message = gson.toJson(new Event(
                event.kind().name(),
                api.view(event.player()),
                event.from() == null ? null : event.from().name(),
                event.to() == null ? null : event.to().name()));
        for (WsContext socket : sockets) {
            try {
                socket.send(message);
            } catch (RuntimeException e) {
                sockets.remove(socket);
                LOG.debug("Dropped a dashboard client that would not accept an event", e);
            }
        }
    }

    /** One envelope for every event type, so a client opens one socket. */
    private record Event(String type, DashboardApi.PlayerView player, String from, String to) {
    }

    public void stop() {
        proxy.events().removeListener(listener);
        sockets.clear();
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    /** @return the port actually bound, or -1 if the dashboard is not running */
    public int port() {
        return server == null ? -1 : server.port();
    }

    /**
     * Javalin's JSON hook, backed by the Gson already on the classpath.
     *
     * <p>Javalin defaults to Jackson, which is not a dependency here. Adding one for
     * serialising four record types, when Gson is already present for text components,
     * would be a second JSON library earning its place by doing nothing new.
     */
    private JsonMapper gsonMapper() {
        return new JsonMapper() {
            @Override
            public @NotNull String toJsonString(@NotNull Object obj, @NotNull Type type) {
                return gson.toJson(obj, type);
            }

            @Override
            public <T> @NotNull T fromJsonString(@NotNull String json, @NotNull Type targetType) {
                return gson.fromJson(json, targetType);
            }
        };
    }
}
