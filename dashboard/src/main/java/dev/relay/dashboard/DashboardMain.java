package dev.relay.dashboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.relay.dashboard.auth.Auth;
import dev.relay.dashboard.auth.Sessions;
import dev.relay.dashboard.auth.UserStore;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JsonMapper;
import io.javalin.websocket.WsContext;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
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

    /** The right shape with nothing in it, for when the proxy cannot be reached. */
    private static final String EMPTY_PAGE =
            "{\"total\":0,\"matched\":0,\"offset\":0,\"limit\":0,\"players\":[],\"edges\":[]}";

    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final Set<WsContext> browsers = ConcurrentHashMap.newKeySet();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final CountDownLatch stopped = new CountDownLatch(1);

    private ControlClient control;
    private Javalin server;
    private Auth auth;

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

        UserStore users = new UserStore(Path.of(env("RELAY_DASHBOARD_USERS", "dashboard-users.json")));
        try {
            users.load();
        } catch (IOException broken) {
            // Refusing to start beats starting with no accounts. A store that fell back to
            // "nobody is configured" on a syntax error would answer a typo by taking the
            // lock off the door.
            LOG.error("Cannot read {}: {}", users.file(), broken.getMessage());
            return;
        }

        boolean loopback = LOOPBACK.contains(bindHost.toLowerCase(Locale.ROOT));
        boolean required = !users.isEmpty();
        if (!required && !loopback) {
            LOG.error("Refusing to bind {} with no accounts configured. An unauthenticated "
                            + "dashboard on a reachable address tells anyone who finds it who is "
                            + "online and where. Create an account first:", bindHost);
            LOG.error("    py relay.py dashboard-user add <name>");
            LOG.error("or bind to 127.0.0.1 and reach it through an SSH tunnel.");
            return;
        }
        auth = new Auth(users, required);

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
        auth.install(server);
        routes();
        server.start(bindHost, bindPort);

        LOG.info("Dashboard on http://{}:{} ({})", bindHost, bindPort,
                required ? users.all().size() + " account(s), sign-in required" : "open, loopback only");
        if (required && !loopback) {
            LOG.warn("Bound to {} and serving over plain HTTP. Passwords and session cookies "
                            + "cross the network in the clear unless something in front of this "
                            + "terminates TLS (spec 11.2 suggests Caddy or nginx).", bindHost);
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
        for (String what : new String[] {"overview", "servers", "groups", "metrics", "log"}) {
            server.get("/api/" + what, ctx -> ctx.contentType("application/json").result(ask(what)));
        }

        // The player list is a page, narrowed by the proxy rather than by the browser.
        // Sending everyone and filtering in JavaScript was fine for a test network and
        // is not survivable on a real one: it is the whole player list, each row
        // carrying a freshly walked route, rebuilt every five seconds per open tab.
        //
        // Uncached for the same reason /api/history is -- the answer depends on the
        // arguments, and one cache slot per endpoint would serve one tab's filter to
        // the next tab. Paging is what made that affordable.
        server.get("/api/players", ctx -> {
            JsonObject narrow = new JsonObject();
            narrow.addProperty("q", ctx.queryParam("q"));
            narrow.addProperty("server", ctx.queryParam("server"));
            narrow.addProperty("offset", parseOffset(ctx.queryParam("offset")));
            narrow.addProperty("limit", parseLimit(ctx.queryParam("limit")));
            JsonElement data = control.query("players", narrow);
            ctx.contentType("application/json")
                    .result(data == null || data.isJsonNull() ? EMPTY_PAGE : gson.toJson(data));
        });

        // History takes arguments, so it is not part of the cached pass-through above:
        // caching one player's sessions under the key "history" would answer the next
        // player's question with the previous player's answer.
        server.get("/api/history", ctx -> {
            JsonObject narrow = new JsonObject();
            String uuid = ctx.queryParam("uuid");
            if (uuid != null && !uuid.isBlank()) {
                narrow.addProperty("uuid", uuid);
            }
            narrow.addProperty("limit", parseLimit(ctx.queryParam("limit")));
            JsonElement data = control.query("history", narrow);
            ctx.contentType("application/json")
                    .result(data == null || data.isJsonNull() ? "[]" : gson.toJson(data));
        });

        // Recent actions and who took them (spec 11.2). Readable by anyone who can see
        // the dashboard: an audit log that only the people who can act may read is an
        // audit log that cannot be used to check up on them.
        server.get("/api/audit", ctx -> {
            JsonObject narrow = new JsonObject();
            narrow.addProperty("limit", parseLimit(ctx.queryParam("limit")));
            JsonElement data = control.query("audit", narrow);
            ctx.contentType("application/json")
                    .result(data == null || data.isJsonNull() ? "[]" : gson.toJson(data));
        });

        actions();

        server.ws("/api/events", ws -> {
            ws.onConnect(ctx -> {
                // The before-handler does not run for a websocket upgrade, so the session
                // is checked here. Without this the page's live feed would be readable by
                // anyone who could open a socket, which is every bit as much a leak as
                // the REST endpoints it mirrors.
                if (auth.required() && auth.sessions().touch(ctx.cookie(Sessions.COOKIE)).isEmpty()) {
                    ctx.closeSession();
                    return;
                }
                // No timeout: these are long-lived by design, and Jetty's default would
                // close an idle one, which is exactly what a quiet network looks like.
                ctx.session.setIdleTimeout(Duration.ZERO);
                browsers.add(ctx);
            });
            ws.onClose(ctx -> browsers.remove(ctx));
            ws.onError(ctx -> browsers.remove(ctx));
        });
    }

    /**
     * The endpoints that change something (spec 9.3).
     *
     * <p>Every one of them is the same four steps in the same order, which is why they are
     * written through one helper rather than four times: check CSRF, check the permission
     * node, ask the proxy, report what it said. A list of actions where one of them does
     * those in a different order, or skips one, is how an action surface grows a hole.
     *
     * <p>The permission nodes are spec 9.7's, the ones {@code UserStore} has been handing
     * out since before anything could act. Moving people between backends and disconnecting
     * them are a moderator's; draining a backend is as well, because taking one out of
     * rotation is the safe half of a restart and waiting for an admin to wake up is how it
     * gets done with a kill instead.
     */
    private void actions() {
        act("/api/actions/drain", "relay.server.drain", (ctx, request) -> {
            request.addProperty("action", "drain");
            request.addProperty("target", ctx.queryParam("server"));
            request.addProperty("on", !"false".equals(ctx.queryParam("on")));
        });
        act("/api/actions/send", "relay.player.send", (ctx, request) -> {
            request.addProperty("action", "send");
            request.addProperty("target", ctx.queryParam("player"));
            request.addProperty("to", ctx.queryParam("to"));
        });
        act("/api/actions/evacuate", "relay.player.send", (ctx, request) -> {
            request.addProperty("action", "evacuate");
            request.addProperty("target", ctx.queryParam("server"));
            request.addProperty("to", ctx.queryParam("to"));
        });
        act("/api/actions/kick", "relay.player.kick", (ctx, request) -> {
            request.addProperty("action", "kick");
            request.addProperty("target", ctx.queryParam("player"));
            request.addProperty("reason", ctx.queryParam("reason"));
        });
    }

    private interface Request {
        void fill(Context ctx, JsonObject request);
    }

    private void act(String path, String node, Request fill) {
        server.post(path, ctx -> {
            // CSRF first. A request that should never have reached this endpoint is not
            // one to answer questions about -- including whether the account it carried
            // would have been allowed.
            if (!auth.csrfOk(ctx)) {
                LOG.warn("Refused {} from {}: the CSRF token was missing or wrong",
                        path, ctx.ip());
                ctx.status(403).json(Map.of("ok", false, "message",
                        "This page's security token is stale. Reload and try again."));
                return;
            }
            if (!auth.may(ctx, node)) {
                String who = actor(ctx);
                LOG.warn("Refused {} for {}: {} is not held", path, who, node);
                ctx.status(403).json(Map.of("ok", false, "message",
                        "Your account does not hold " + node + "."));
                return;
            }

            JsonObject request = new JsonObject();
            request.addProperty("by", actor(ctx));
            fill.fill(ctx, request);
            JsonObject done = control.act(request);

            // 409 rather than 400 for a refusal: the request was well formed and the
            // caller is allowed: the network was simply not in a state where it made
            // sense. A page that shows the proxy's own sentence either way needs the
            // difference in the status, not the body.
            boolean ok = done.has("ok") && done.get("ok").getAsBoolean();
            ctx.status(ok ? 200 : 409).contentType("application/json").result(gson.toJson(done));
        });
    }

    /**
     * Who to write in the audit row.
     *
     * <p>Named honestly when there is nobody signed in. That only happens in the
     * loopback-with-no-accounts mode, which is the development default, and an audit log
     * that said "admin" for it would be inventing a person.
     */
    private String actor(Context ctx) {
        return auth.user(ctx).map(UserStore.User::username)
                .orElse(auth.required() ? "unknown" : "anonymous (no accounts configured)");
    }

    /** Clamped here as well as in the proxy: a companion should not be able to ask for a million rows. */
    private static int parseLimit(String raw) {
        try {
            return Math.max(1, Math.min(Integer.parseInt(raw), 200));
        } catch (RuntimeException notANumber) {
            return 50;
        }
    }

    private static int parseOffset(String raw) {
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (RuntimeException notANumber) {
            return 0;
        }
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
        return "overview".equals(what) || "metrics".equals(what) ? "null" : "[]";
    }

    /** Forwards a proxy event to every browser watching, unchanged. */
    private void onEvent(JsonObject event) {
        // A log line says nothing about players or backends, and at DEBUG they arrive
        // dozens a second. Clearing the cache for each one would turn the console into a
        // load generator against the very proxy it is reporting on.
        boolean isLog = event.has("type") && "log".equals(event.get("type").getAsString());
        if (!isLog) {
            cache.clear();
        }
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
