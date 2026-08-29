package dev.relay.dashboard.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dashboard sign-in: hashing, accounts, sessions and the throttle.
 *
 * <p>The passwords below are test fixtures. They exist to be wrong in the next assertion,
 * and none of them is a credential for anything.
 */
class AuthTest {

    private static final char[] SECRET = "correct-horse-battery".toCharArray();

    // -------------------------------------------------------------------- hashing

    @Test
    void aPasswordMatchesItsOwnHashAndNothingElse() {
        String stored = PasswordHash.hash(SECRET);
        assertTrue(PasswordHash.matches(SECRET, stored));
        assertFalse(PasswordHash.matches("correct-horse-batter".toCharArray(), stored));
        assertFalse(PasswordHash.matches(new char[0], stored));
    }

    @Test
    void theSamePasswordHashesDifferentlyEveryTime() {
        assertNotEquals(PasswordHash.hash(SECRET), PasswordHash.hash(SECRET),
                "without a per-password salt, identical passwords are visibly identical "
                        + "in the file and one cracked hash cracks every account that shares it");
    }

    @Test
    void theStoredFormCarriesItsOwnParameters() {
        String stored = PasswordHash.hash(SECRET);
        String[] parts = stored.split("\\$");
        assertEquals(4, parts.length, stored);
        assertEquals("pbkdf2-sha512", parts[0], "a hash that does not say how it was made "
                + "cannot survive the settings changing");
        assertTrue(Integer.parseInt(parts[1]) >= 100_000, "iteration count too low: " + parts[1]);
        assertFalse(PasswordHash.needsRehash(stored));
    }

    /** A corrupt line is a password that cannot be right, not a 500 for everyone. */
    @Test
    void aMalformedHashIsRejectedRatherThanThrowing() {
        for (String broken : new String[] {"", "nonsense", "pbkdf2-sha512$x$y$z",
                                           "bcrypt$1$2$3", "pbkdf2-sha512$1000$!!$!!"}) {
            assertFalse(PasswordHash.matches(SECRET, broken), broken);
        }
        assertFalse(PasswordHash.matches(SECRET, null));
        assertTrue(PasswordHash.needsRehash("nonsense"));
    }

    // -------------------------------------------------------------------- accounts

    @Test
    void accountsSurviveASaveAndReload(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("users.json");
        UserStore store = new UserStore(file);
        store.put("Carson", SECRET, "admin");
        store.save();

        UserStore reloaded = new UserStore(file);
        reloaded.load();
        assertEquals(1, reloaded.all().size());
        assertTrue(reloaded.authenticate("carson", SECRET).isPresent());
        assertTrue(reloaded.authenticate("CARSON", SECRET).isPresent(),
                "usernames are compared case-insensitively, so a capital letter at the "
                        + "login box is not a lockout");
        assertTrue(reloaded.authenticate("carson", "wrong".toCharArray()).isEmpty());
    }

    @Test
    void theStoredFileNeverContainsThePassword(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("users.json");
        UserStore store = new UserStore(file);
        store.put("carson", SECRET, "admin");
        store.save();

        String json = Files.readString(file);
        assertFalse(json.contains("correct-horse-battery"), json);
    }

    /** A syntax error must not read as "no accounts", which would unlock the door. */
    @Test
    void abrokenFileIsRefusedRatherThanIgnored(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("users.json");
        Files.writeString(file, "{ this is not json");
        UserStore store = new UserStore(file);
        assertThrows(IOException.class, store::load);

        Files.writeString(file, "{\"version\":1}");
        assertThrows(IOException.class, store::load, "a document with no users list is not empty, "
                + "it is wrong, and the difference decides whether the dashboard is open");
    }

    @Test
    void aMissingFileIsSimplyNoAccounts(@TempDir Path dir) throws IOException {
        UserStore store = new UserStore(dir.resolve("absent.json"));
        store.load();
        assertTrue(store.isEmpty());
    }

    @Test
    void rolesGrantTheNodesTheyName(@TempDir Path dir) throws IOException {
        UserStore store = new UserStore(dir.resolve("users.json"));
        store.put("viewer", SECRET, "viewer");
        store.put("mod", SECRET, "moderator");
        store.put("boss", SECRET, "admin");

        assertTrue(store.find("viewer").orElseThrow().has("relay.dashboard.view"));
        assertFalse(store.find("viewer").orElseThrow().has("relay.player.kick"));
        assertTrue(store.find("mod").orElseThrow().has("relay.player.kick"));
        assertFalse(store.find("mod").orElseThrow().has("relay.config.edit"));
        assertTrue(store.find("boss").orElseThrow().has("relay.config.edit"),
                "relay.* should grant anything under relay.");
    }

    @Test
    void anUnknownRoleIsRefused(@TempDir Path dir) {
        UserStore store = new UserStore(dir.resolve("users.json"));
        assertThrows(IllegalArgumentException.class, () -> store.put("x", SECRET, "superuser"));
        assertThrows(IllegalArgumentException.class, () -> store.put("  ", SECRET, "admin"));
    }

    // -------------------------------------------------------------------- sessions

    @Test
    void aSessionIsValidUntilItIsDestroyed() {
        Sessions sessions = new Sessions();
        String token = sessions.create("carson");

        assertEquals("carson", sessions.touch(token).orElseThrow().username());
        sessions.destroy(token);
        assertTrue(sessions.touch(token).isEmpty());
    }

    @Test
    void unknownTokensAreNotSessions() {
        Sessions sessions = new Sessions();
        assertTrue(sessions.touch(null).isEmpty());
        assertTrue(sessions.touch("").isEmpty());
        assertTrue(sessions.touch("made-up").isEmpty());
    }

    @Test
    void twoSessionsNeverShareATokenOrACsrf() {
        Sessions sessions = new Sessions();
        String first = sessions.create("carson");
        String second = sessions.create("carson");
        assertNotEquals(first, second);
        assertNotEquals(sessions.touch(first).orElseThrow().csrf(),
                sessions.touch(second).orElseThrow().csrf());
    }

    /** Removing an account has to end the sessions it already had. */
    @Test
    void everySessionForOneAccountCanBeEnded() {
        Sessions sessions = new Sessions();
        String mine = sessions.create("carson");
        String theirs = sessions.create("someone-else");

        sessions.destroyAllFor("Carson");

        assertTrue(sessions.touch(mine).isEmpty(),
                "an account that is removed must stop being logged in");
        assertTrue(sessions.touch(theirs).isPresent(), "and nobody else should be affected");
    }

    // ------------------------------------------------------------------ throttling

    @Test
    void repeatedFailuresLockAnAddressOutAndSuccessClearsIt() {
        Sessions sessions = new Sessions();
        assertEquals(0, sessions.lockedFor("10.0.0.1"));

        for (int i = 0; i < 8; i++) {
            sessions.recordFailure("10.0.0.1");
        }
        assertTrue(sessions.lockedFor("10.0.0.1") > 0, "eight wrong passwords should cost a wait");
        assertEquals(0, sessions.lockedFor("10.0.0.2"), "one address must not lock out another");

        sessions.recordSuccess("10.0.0.1");
        assertEquals(0, sessions.lockedFor("10.0.0.1"),
                "an operator who mistypes and then gets it right is not an attacker");
    }

    /**
     * A wrong username must cost the same work as a wrong password.
     *
     * <p>Timing rather than a return value: if a missing account returns before hashing,
     * an attacker learns which names exist by measuring, without ever logging in. Compared
     * loosely, because a strict ratio on a shared CI machine would be flaky, and the
     * failure being guarded against is a difference of orders of magnitude.
     */
    @Test
    void anUnknownUsernameCostsTheSameAsAWrongPassword(@TempDir Path dir) throws IOException {
        UserStore store = new UserStore(dir.resolve("users.json"));
        store.put("carson", SECRET, "admin");

        long known = time(() -> store.authenticate("carson", "wrong".toCharArray()));
        long unknown = time(() -> store.authenticate("nobody-here", "wrong".toCharArray()));

        assertTrue(unknown * 4 > known,
                "a missing account returned in " + unknown + "ms against " + known
                        + "ms for a wrong password; that gap enumerates accounts");
    }

    private static long time(Runnable work) {
        work.run();                       // warm the key factory, not the measurement
        long start = System.nanoTime();
        work.run();
        return Math.max(1, (System.nanoTime() - start) / 1_000_000);
    }

    @Test
    void authenticateReturnsTheAccountItMatched(@TempDir Path dir) throws IOException {
        UserStore store = new UserStore(dir.resolve("users.json"));
        store.put("carson", SECRET, "moderator");
        Optional<UserStore.User> user = store.authenticate("carson", SECRET);
        assertEquals("moderator", user.orElseThrow().role());
    }
}
