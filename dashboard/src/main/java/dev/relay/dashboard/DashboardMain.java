package dev.relay.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JsonMapper;
import io.javalin.websocket.WsContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * The Relay dashboard, as its own program.
 *
 * <p>It lives outside the proxy because everything it needs to do its job &mdash; Jetty,
 * Javalin, and the Kotlin runtime Javalin is written in &mdash; comes to four and a half
 * megabytes, against about two hundred kilobytes for Relay's own code. None of that
 * belongs in the process carrying player traffic, where a leak or a crash would cost
 * players their session rather than an operator a page refresh.
 *
 * <p>It learns everything through {@link ControlClient}: one loopback socket, newline
 * JSON, a token from the environment. It holds no reference to any proxy class, and the
 * build has no dependency on that module, so the control protocol is the only contract
 * between them and cannot quietly grow a second one.
 *
 * <h2>Running it</h2>
 * Normally Relay starts it, and passes the port and token through the environment. It can
 * also be run by hand against a running proxy, which is what makes it debuggable:
 *
 * <pre>
 * RELAY_CONTROL_PORT=25580 RELAY_CONTROL_TOKEN=... java -jar relay-dashboard.jar
 * </pre>
 */
public final class DashboardMain {

    private static final Logger LOG = LoggerFactory.getLogger(DashboardMain.class);

    private static final Set<String> LOOPBACK = Set.of("127.0.0.1", "::1", "localhost");

    /**
     * How long an answer from the proxy is reused.
     *
     * <p>A browser asks for four collections on every refresh and again every five
     * seconds, and several tabs multiply that. Half a second collapses a burst into one
     * round trip while staying far below anything a person perceives as stale.
     */
    private static final long CACHE_MILLIS = 500;

    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final Set<WsContext> browsers = ConcurrentHashMap.newKeySet();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final CountDownLatch stopped = new CountDownLatch(1);

    private ControlClient control;
    private Javalin server;

    public static void main(String[] args) throws InterruptedException {
        new DashboardMain().run();
    }

    private void run() throws InterruptedException {
        String host = env("RELAY_CONTROL_HOST", "127.0.0.1");
        int controlPort = Integer.parseInt(env("RELAY_CONTROL_PORT", "25580"));
        String token = System.getenv("RELAY_CONTROL_TOKEN");
        if (token == null || token.isBlank()) {
            LOG.error("RELAY_CONTROL_TOKEN is not set. This process is normally started by Relay, "
                    + "which passes the token through the environment; to run it by hand, copy the "
                    + "token the proxy logged at startup.");
            return;
        }

        String bindHost = env("RELAY_DASHBOARD_HOST", "127.0.0.1");
        int bindPort = Integer.parseInt(env("RELAY_DASHBOARD_PORT", "8080"));

        control = new ControlClient(host, controlPort, token, this::onEvent, stopped::countDown);
        control.start();

        server = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.jsonMapper(gsonMapper());
            config.staticFiles.add(files -> {
                files.hostedPath = "/";
                files.directory = "/web";
                files.location = Location.CLASSPATH;
            });
        });
        routes();
        server.start(bindHost, bindPort);

        LOG.info("Dashboard on http://{}:{}", bindHost, bindPort);
        if (!LOOPBACK.contains(bindHost.toLowerCase(Locale.ROOT))) {
            LOG.warn("Bound to {} rather than loopback, and there is no authentication yet. Anyone who "
                            + "can reach that address can see who is online and where. Put it behind "
                            + "something that authenticates, or leave it on loopback and use an SSH tunnel.",
                    bindHost);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(stopped::countDown));
        stopped.await();
        shutdown();
    }

    private void routes() {
        // Straight through to the proxy, with a short cache. The shapes are the proxy's,
        // not this process's: the dashboard renders what Relay reports and invents nothing.
        server.get("/api/health", ctx -> ctx.json(Map.of(
                "status", control.isConnected() ? "ok" : "disconnected")));
        for (String what : new String[] {"overview", "servers", "groups", "players"}) {
            server.get("/api/" + what, ctx -> ctx.contentType("application/json").result(ask(what)));
        }

        server.ws("/api/events", ws -> {
            ws.onConnect(ctx -> {
                // No timeout: these are long-lived by design, and Jetty's default would
                // close an idle one, which is exactly what a quiet network looks like.
                ctx.session.setIdleTimeout(Duration.ZERO);
                browsers.add(ctx);
            });
            ws.onClose(ctx -> browsers.remove(ctx));
            ws.onError(ctx -> browsers.remove(ctx));
        });
    }

    private String ask(String what) {
        Cached cached = cache.get(what);
        if (cached != null && System.currentTimeMillis() - cached.at < CACHE_MILLIS) {
            return cached.json;
        }
        JsonElement data = control.query(what);
        String json = data == null || data.isJsonNull() ? emptyFor(what) : gson.toJson(data);
        cache.put(what, new Cached(json, System.currentTimeMillis()));
        return json;
    }

    /**
     * What to show when the proxy cannot be reached.
     *
     * <p>The right shape, empty, rather than an error. The page already reports the
     * connection separately, and a client that has to handle both "no data" and "an error
     * object where a list belongs" will get one of them wrong.
     */
    private static String emptyFor(String what) {
        return "overview".equals(what) ? "null" : "[]";
    }

    /** Forwards a proxy event to every browser watching, unchanged. */
    private void onEvent(JsonObject event) {
        cache.clear();
        if (browsers.isEmpty()) {
            return;
        }
        String message = event.toString();
        for (WsContext browser : browsers) {
            try {
                browser.send(message);
            } catch (RuntimeException e) {
                browsers.remove(browser);
            }
        }
    }

    private void shutdown() {
        LOG.info("Dashboard stopping");
        browsers.clear();
        if (server != null) {
            server.stop();
        }
        if (control != null) {
            control.close();
        }
    }

    private record Cached(String json, long at) {
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Javalin defaults to Jackson; Gson is already here for the control protocol. */
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
