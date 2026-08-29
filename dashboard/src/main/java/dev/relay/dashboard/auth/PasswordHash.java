package dev.relay.dashboard.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/**
 * Password hashing for dashboard accounts.
 *
 * <p>PBKDF2-HMAC-SHA512, in the JDK, with a per-password salt and the iteration count
 * stored alongside the hash so it can be raised later without invalidating what is
 * already written.
 *
 * <h2>Why not argon2</h2>
 * Spec &sect;9.6 suggests {@code argon2-jvm}, and argon2 is the better algorithm: it is
 * memory-hard, so it resists GPU attack in a way PBKDF2 does not. It is not used here
 * because {@code argon2-jvm} ships a native library through JNA, and this is a companion
 * process an operator drops onto a homelab box &mdash; a native dependency turns "run the
 * jar" into "run the jar, on a platform we built a binary for".
 *
 * <p>That trade is only defensible because of what it is defending. These are a handful of
 * local operator accounts behind login throttling on a service that should be behind TLS
 * and, in most deployments, not exposed at all. It is not a user database. If this ever
 * holds more than a few accounts, argon2 is the upgrade, and the stored format below
 * already carries an algorithm field so both can coexist.
 *
 * <h2>Format</h2>
 * <pre>pbkdf2-sha512$&lt;iterations&gt;$&lt;salt-b64&gt;$&lt;hash-b64&gt;</pre>
 * One self-describing string, so a stored password carries the parameters it was made
 * with. A file of bare hashes cannot survive its own settings changing.
 */
public final class PasswordHash {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA512";
    private static final String LABEL = "pbkdf2-sha512";

    /**
     * Cost. Roughly a tenth of a second on a modern core, which is the usual balance:
     * unnoticeable to someone logging in, expensive to someone guessing.
     */
    private static final int ITERATIONS = 210_000;

    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 512;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHash() {
    }

    public static String hash(char[] password) {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] key = derive(password, salt, ITERATIONS);
        return LABEL + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(key);
    }

    /**
     * @return true if {@code password} produced {@code stored}
     *
     * <p>Returns false rather than throwing on a malformed record. A stored value that
     * cannot be parsed is a password that cannot be right, and an exception here would
     * turn one corrupt line into a login endpoint that 500s for everybody.
     */
    public static boolean matches(char[] password, String stored) {
        if (stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 4 || !LABEL.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = derive(password, salt, iterations);
            // Constant time: a comparison that returns early leaks how much of the hash
            // was right, one byte at a time.
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    /** True when a stored hash was made with settings weaker than the current ones. */
    public static boolean needsRehash(String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\$");
        if (parts.length != 4 || !LABEL.equals(parts[0])) {
            return true;
        }
        try {
            return Integer.parseInt(parts[1]) < ITERATIONS;
        } catch (NumberFormatException malformed) {
            return true;
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (Exception e) {
            // PBKDF2WithHmacSHA512 is required of every conforming JRE. If it is missing,
            // nothing about this process is going to work and pretending otherwise would
            // mean silently accepting logins.
            throw new IllegalStateException("this JVM cannot hash passwords: " + ALGORITHM, e);
        } finally {
            spec.clearPassword();
        }
    }

    /** Usernames are compared case-insensitively, so they are stored that way. */
    public static String normalise(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
