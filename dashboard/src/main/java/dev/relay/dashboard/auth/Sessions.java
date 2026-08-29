package dev.relay.dashboard.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logged-in sessions, and the throttle that guards the way in.
 *
 * <h2>Why not JWT</h2>
 * Spec &sect;9.6 suggests {@code jjwt}. A JWT is the right answer when the thing checking
 * the token cannot ask the thing that issued it &mdash; several services, or a stateless
 * fleet. Here there is one process, checking tokens it issued itself, and the property
 * that actually matters is being able to <em>revoke</em>: an operator who is removed
 * should stop being logged in, and a signed self-contained token cannot be taken back
 * without keeping exactly the server-side state a JWT was chosen to avoid.
 *
 * <p>So: an opaque 256-bit random token, and a map. Simpler, revocable, and nothing to get
 * wrong about algorithm confusion or {@code alg: none}.
 *
 * <h2>Throttling</h2>
 * Spec &sect;11.2 asks for login throttling. Counted per source address, and the counter is
 * cleared by a success so an operator who mistypes twice is not locked out for the day.
 */
public final class Sessions {

    /** How long a session lasts without being used. Spec 11.2 asks for short-lived. */
    private static final long IDLE_TIMEOUT_MILLIS = 12 * 60 * 60 * 1000L;

    /** Wrong passwords from one address before it has to wait. */
    private static final int MAX_ATTEMPTS = 8;

    /** How long that address then waits. */
    private static final long LOCKOUT_MILLIS = 5 * 60 * 1000L;

    /** The cookie the browser holds. Named so it cannot collide with a backend's. */
    public static final String COOKIE = "relay_session";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    /** @param csrf a second secret, sent to the page and required back on every write */
    public record Session(String username, String csrf, long createdAt, long lastSeenAt) {
    }

    private record Attempts(int count, long until) {
    }

    public String create(String username) {
        String token = randomToken();
        long now = System.currentTimeMillis();
        sessions.put(token, new Session(username, randomToken(), now, now));
        return token;
    }

    /**
     * @return the session for this token, sliding its expiry forward
     *
     * <p>Expired sessions are dropped on the way past rather than by a sweeper thread.
     * A map that is only read when someone is using it does not need one, and a timer
     * whose only job is to delete a handful of entries is a thread that can go wrong.
     */
    public Optional<Session> touch(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        if (now - session.lastSeenAt() > IDLE_TIMEOUT_MILLIS) {
            sessions.remove(token);
            return Optional.empty();
        }
        Session slid = new Session(session.username(), session.csrf(), session.createdAt(), now);
        sessions.put(token, slid);
        return Optional.of(slid);
    }

    public void destroy(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** Ends every session belonging to one account, for when it is removed or changed. */
    public void destroyAllFor(String username) {
        String target = PasswordHash.normalise(username);
        sessions.entrySet().removeIf(entry -> entry.getValue().username().equals(target));
    }

    public int count() {
        return sessions.size();
    }

    // ----------------------------------------------------------------- throttling

    /** @return milliseconds this address must still wait, or 0 if it may try now */
    public long lockedFor(String address) {
        Attempts record = attempts.get(address);
        if (record == null || record.count() < MAX_ATTEMPTS) {
            return 0;
        }
        long remaining = record.until() - System.currentTimeMillis();
        if (remaining <= 0) {
            attempts.remove(address);
            return 0;
        }
        return remaining;
    }

    public void recordFailure(String address) {
        attempts.compute(address, (key, existing) -> {
            int count = existing == null ? 1 : existing.count() + 1;
            return new Attempts(count, System.currentTimeMillis() + LOCKOUT_MILLIS);
        });
    }

    public void recordSuccess(String address) {
        attempts.remove(address);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
