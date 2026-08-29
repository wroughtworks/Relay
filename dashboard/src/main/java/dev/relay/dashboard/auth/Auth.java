package dev.relay.dashboard.auth;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.Cookie;
import io.javalin.http.SameSite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Who is allowed in, and how they prove it.
 *
 * <p>The rule this enforces above all others: <b>the dashboard will not serve anything to
 * an unauthenticated request unless it is bound to loopback and has no accounts</b>. That
 * combination is the only unauthenticated mode, it is the development default, and
 * {@code DashboardMain} refuses to start off-loopback without accounts rather than quietly
 * exposing a page that lists everyone on the network.
 *
 * <p>Everything here is deliberately ordinary: an opaque session cookie, a hash, a
 * throttle. Spec &sect;11.2 asks for password hashing, short-lived sessions, API
 * authorization, login throttling, audit logging and CSRF protection; each of those is one
 * small mechanism, and the value is in none of them being clever.
 */
public final class Auth {

    private static final Logger LOG = LoggerFactory.getLogger(Auth.class);

    /** Reachable without a session. Everything else needs one. */
    private static final Set<String> PUBLIC = Set.of("/login", "/logout", "/api/health");

    /** Header a write request must echo the session's CSRF token in. */
    public static final String CSRF_HEADER = "X-Relay-CSRF";

    private final UserStore users;
    private final Sessions sessions = new Sessions();
    private final boolean required;

    /**
     * @param required false only when there are no accounts and the bind is loopback, in
     *                 which case every request is treated as an anonymous administrator.
     *                 That is the development mode, and it is named honestly rather than
     *                 emerging from a missing check
     */
    public Auth(UserStore users, boolean required) {
        this.users = users;
        this.required = required;
    }

    public boolean required() {
        return required;
    }

    public Sessions sessions() {
        return sessions;
    }

    public void install(Javalin app) {
        app.before(ctx -> {
            if (!required || isPublic(ctx)) {
                return;
            }
            if (session(ctx).isPresent()) {
                return;
            }
            // An API caller wants a status it can act on; a browser wants the login page.
            // Redirecting XHR to HTML is how a dashboard ends up rendering a login form
            // inside a table cell.
            if (ctx.path().startsWith("/api/")) {
                ctx.status(401).contentType("application/json")
                        .result("{\"error\":\"not signed in\"}");
            } else {
                ctx.redirect("/login");
            }
            ctx.skipRemainingHandlers();
        });

        app.get("/login", ctx -> {
            if (!required) {
                ctx.redirect("/");
                return;
            }
            ctx.contentType("text/html").result(page(null));
        });

        app.post("/login", this::signIn);
        app.post("/logout", ctx -> {
            String token = ctx.cookie(Sessions.COOKIE);
            session(ctx).ifPresent(s -> LOG.info("Dashboard sign-out: {}", s.username()));
            sessions.destroy(token);
            ctx.removeCookie(Sessions.COOKIE, "/");
            ctx.redirect("/login");
        });

        // Who am I, and what may I do. The page needs the CSRF token from here before it
        // can make any write request, so this is also how that token reaches the browser
        // -- never in a cookie, which would defeat the point of having it.
        app.get("/api/me", ctx -> {
            Optional<Sessions.Session> session = session(ctx);
            if (session.isEmpty()) {
                ctx.json(Map.of("authenticated", false, "required", required));
                return;
            }
            UserStore.User user = users.find(session.get().username()).orElse(null);
            ctx.json(Map.of(
                    "authenticated", true,
                    "required", required,
                    "username", session.get().username(),
                    "role", user == null ? "unknown" : user.role(),
                    "nodes", user == null ? java.util.List.of() : user.nodes(),
                    "csrf", session.get().csrf()));
        });
    }

    private void signIn(Context ctx) {
        String address = ctx.ip();
        long wait = sessions.lockedFor(address);
        if (wait > 0) {
            // Told plainly. Hiding a lockout only means an operator retries in confusion,
            // and an attacker already knows: they can measure it.
            LOG.warn("Dashboard sign-in refused: {} is throttled for another {}s",
                    address, wait / 1000);
            ctx.status(429).contentType("text/html")
                    .result(page("Too many attempts. Try again in "
                            + Math.max(1, wait / 1000) + " seconds."));
            return;
        }

        String username = ctx.formParam("username");
        String password = ctx.formParam("password");
        char[] characters = password == null ? new char[0] : password.toCharArray();

        Optional<UserStore.User> user = users.authenticate(
                username == null ? "" : username, characters);
        java.util.Arrays.fill(characters, '\0');

        if (user.isEmpty()) {
            sessions.recordFailure(address);
            // Audit trail, spec 11.2. The username is logged because "who was someone
            // trying to be" is the question an operator asks; the password never is.
            LOG.warn("Dashboard sign-in failed for '{}' from {}", username, address);
            ctx.status(401).contentType("text/html").result(page("Wrong username or password."));
            return;
        }

        sessions.recordSuccess(address);
        String token = sessions.create(user.get().username());
        LOG.info("Dashboard sign-in: {} ({}) from {}",
                user.get().username(), user.get().role(), address);

        ctx.cookie(new Cookie(Sessions.COOKIE, token, "/", -1,
                // Secure only when the request arrived over TLS. Setting it
                // unconditionally would make the cookie unusable over the plain HTTP an
                // operator uses through an SSH tunnel, and the browser would simply
                // discard it with no error anyone can see.
                "https".equalsIgnoreCase(ctx.scheme()),
                0, true, null, null, SameSite.STRICT));
        ctx.redirect("/");
    }

    /** @return the live session for this request, sliding its expiry */
    public Optional<Sessions.Session> session(Context ctx) {
        return sessions.touch(ctx.cookie(Sessions.COOKIE));
    }

    /** @return the signed-in user, or empty when anonymous */
    public Optional<UserStore.User> user(Context ctx) {
        return session(ctx).flatMap(s -> users.find(s.username()));
    }

    /**
     * True when this request may perform {@code node}.
     *
     * <p>Unused while the API is read-only, and deliberately written now: the moment a
     * write endpoint exists, the question "who may do this" should have an answer that
     * predates the endpoint rather than one invented alongside it.
     */
    public boolean may(Context ctx, String node) {
        if (!required) {
            return true;
        }
        return user(ctx).map(user -> user.has(node)).orElse(false);
    }

    /**
     * Checks the CSRF token on a state-changing request.
     *
     * <p>{@code SameSite=Strict} already stops a browser sending the session cookie on a
     * cross-site request, and this is the belt to that pair of braces: it costs one header
     * and covers the cases SameSite does not, such as a same-site page that should not
     * have been able to reach this endpoint at all.
     */
    public boolean csrfOk(Context ctx) {
        if (!required) {
            return true;
        }
        return session(ctx)
                .map(s -> s.csrf().equals(ctx.header(CSRF_HEADER)))
                .orElse(false);
    }

    private static boolean isPublic(Context ctx) {
        String path = ctx.path();
        return PUBLIC.contains(path) || "/favicon.ico".equals(path);
    }

    /**
     * The sign-in page, self-contained so it needs nothing this page is guarding.
     *
     * <p>Assembled by concatenation rather than {@code String.formatted}. The stylesheet
     * below is full of {@code 50%} and {@code 12%}, and a format string reads every one of
     * those as a conversion it does not recognise -- so the first version of this threw
     * {@code UnknownFormatConversionException} and turned a wrong password into a 500.
     */
    private static String page(String error) {
        String message = error == null ? "" :
                "<p class=\"err\">" + escape(error) + "</p>";
        return HEAD + message + TAIL;
    }

    private static final String HEAD = """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Relay — sign in</title>
                <style>
                  :root { --bg:#fbfbfa; --panel:#fff; --line:#e6e4e0; --text:#1c1b19;
                          --muted:#6b675f; --accent:#b4552d; --bad:#b03030; }
                  @media (prefers-color-scheme: dark) {
                    :root { --bg:#171614; --panel:#1f1e1b; --line:#322f2a; --text:#edeae4;
                            --muted:#9a948a; --accent:#d97757; --bad:#e06c6c; }
                  }
                  * { box-sizing: border-box; }
                  body { margin:0; min-height:100vh; display:grid; place-items:center;
                         background:var(--bg); color:var(--text);
                         font:15px/1.55 ui-sans-serif, system-ui, -apple-system, sans-serif; }
                  form { background:var(--panel); border:1px solid var(--line);
                         border-radius:12px; padding:26px 28px; width:min(340px, 92vw); }
                  h1 { margin:0 0 4px; font-size:20px; letter-spacing:-0.01em; }
                  h1 span { color:var(--accent); }
                  p.sub { margin:0 0 18px; color:var(--muted); font-size:13px; }
                  label { display:block; font-size:12px; color:var(--muted);
                          text-transform:uppercase; letter-spacing:0.06em; margin-bottom:5px; }
                  input { width:100%; padding:9px 11px; font:inherit; margin-bottom:14px;
                          color:var(--text); background:var(--bg);
                          border:1px solid var(--line); border-radius:8px; }
                  input:focus { outline:2px solid color-mix(in srgb, var(--accent) 50%, transparent);
                                outline-offset:-1px; }
                  button { width:100%; padding:9px; font:inherit; font-weight:600; cursor:pointer;
                           color:#fff; background:var(--accent); border:0; border-radius:8px; }
                  .err { margin:0 0 14px; padding:8px 10px; font-size:13px; color:var(--bad);
                         background:color-mix(in srgb, var(--bad) 12%, transparent);
                         border-radius:8px; }
                </style></head>
                <body>
                <form method="post" action="/login">
                  <h1>Rel<span>ay</span></h1>
                  <p class="sub">Sign in to the dashboard</p>
                """;

    private static final String TAIL = """
                  <label for="u">Username</label>
                  <input id="u" name="username" autocomplete="username" autofocus required>
                  <label for="p">Password</label>
                  <input id="p" name="password" type="password"
                         autocomplete="current-password" required>
                  <button type="submit">Sign in</button>
                </form>
                </body></html>
                """;

    /** The error text is ours, but escaping it costs nothing and outlives that fact. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
