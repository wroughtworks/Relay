package dev.relay.dashboard.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The dashboard's local accounts, in a file of its own.
 *
 * <p>Not in {@code relay.toml}. That file is described in its own header as the single
 * source of truth for proxy settings and something you keep in git; password hashes are
 * neither. They also belong to the dashboard rather than the proxy, and the whole point of
 * the companion split is that the two do not reach into each other's state.
 *
 * <h2>Roles</h2>
 * Spec &sect;9.7 names admin, moderator and viewer, and asks that permissions eventually
 * govern dashboard and in-game operations alike. So a role is stored as a list of
 * permission nodes in the same scheme the proxy already uses for commands, matched the
 * same way &mdash; {@code relay.*} grants {@code relay.player.kick}, {@code *} grants
 * everything. Nothing today needs more than "is this person logged in", because the whole
 * API is read-only; the nodes are here so that the day actions arrive, the answer is
 * already written down rather than invented in a hurry.
 */
public final class UserStore {

    /** Every node a viewer gets. Reading is all the dashboard can do today. */
    public static final List<String> VIEWER = List.of("relay.dashboard.view");

    /** A moderator can also move people about, once anything can. */
    public static final List<String> MODERATOR = List.of(
            "relay.dashboard.view", "relay.player.kick", "relay.player.send",
            "relay.server.drain");

    public static final List<String> ADMIN = List.of("relay.*");

    public static final Map<String, List<String>> ROLES = Map.of(
            "viewer", VIEWER, "moderator", MODERATOR, "admin", ADMIN);

    /**
     * One account.
     *
     * @param password the encoded hash from {@link PasswordHash}, never a password
     * @param nodes    the permission nodes this account holds, expanded from its role at
     *                 the time it was created. Stored expanded on purpose: changing what
     *                 "moderator" means later should be a deliberate act on each account,
     *                 not something that silently widens everyone's access on upgrade
     */
    public record User(String username, String password, String role, List<String> nodes) {

        public boolean has(String node) {
            for (String entry : nodes == null ? List.<String>of() : nodes) {
                if (entry.equals("*") || entry.equals(node)) {
                    return true;
                }
                // "relay.*" grants "relay.player.kick"; a bare prefix does not.
                if (entry.endsWith(".*") && node.startsWith(entry.substring(0, entry.length() - 1))) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Document(int version, List<User> users) {
    }

    private static final int VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Map<String, User> users = new LinkedHashMap<>();

    public UserStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /**
     * Reads the file, or starts empty if there is none.
     *
     * @throws IOException if the file exists but cannot be read or understood. A store
     *                     that silently falls back to "no users" on a syntax error would
     *                     turn a typo into an open dashboard, which is the one failure
     *                     mode this class exists to prevent
     */
    public void load() throws IOException {
        users.clear();
        if (!Files.exists(file)) {
            return;
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        Document document;
        try {
            document = GSON.fromJson(json, Document.class);
        } catch (JsonSyntaxException e) {
            throw new IOException(file + " is not valid JSON", e);
        }
        if (document == null || document.users() == null) {
            throw new IOException(file + " has no users list");
        }
        for (User user : document.users()) {
            if (user == null || user.username() == null || user.password() == null) {
                throw new IOException(file + " contains an account with no name or password");
            }
            users.put(PasswordHash.normalise(user.username()), user);
        }
    }

    public void save() throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, GSON.toJson(new Document(VERSION, List.copyOf(users.values()))),
                StandardCharsets.UTF_8);
        restrictPermissions();
    }

    /**
     * Best effort: readable only by its owner.
     *
     * <p>POSIX only, and quietly skipped elsewhere. On Windows the equivalent is an ACL
     * edit that would need a great deal more code to get right, and getting it half right
     * would be worse than being honest that the file's protection is the directory's.
     */
    private void restrictPermissions() {
        try {
            if (Files.getFileStore(file).supportsFileAttributeView(
                    java.nio.file.attribute.PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(file,
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException | UnsupportedOperationException ignored) {
            // The directory is the protection on platforms that cannot do this.
        }
    }

    public Optional<User> find(String username) {
        return Optional.ofNullable(users.get(PasswordHash.normalise(username)));
    }

    public List<User> all() {
        return List.copyOf(users.values());
    }

    public boolean isEmpty() {
        return users.isEmpty();
    }

    /** Adds or replaces an account. The password is hashed here and not kept. */
    public void put(String username, char[] password, String role) {
        String normalised = PasswordHash.normalise(username);
        if (normalised.isEmpty()) {
            throw new IllegalArgumentException("a username is required");
        }
        List<String> nodes = ROLES.get(role == null ? "" : role.toLowerCase(Locale.ROOT));
        if (nodes == null) {
            throw new IllegalArgumentException(
                    "unknown role '" + role + "'; expected one of " + new ArrayList<>(ROLES.keySet()));
        }
        users.put(normalised, new User(normalised, PasswordHash.hash(password), role, nodes));
    }

    public boolean remove(String username) {
        return users.remove(PasswordHash.normalise(username)) != null;
    }

    /**
     * Checks a password, and says who it belongs to.
     *
     * <p>An unknown username still costs a hash. Returning early would make a wrong name
     * measurably faster than a wrong password, which is how an attacker enumerates
     * accounts without ever logging in.
     */
    public Optional<User> authenticate(String username, char[] password) {
        User user = users.get(PasswordHash.normalise(username));
        String stored = user == null ? DUMMY : user.password();
        boolean ok = PasswordHash.matches(password, stored);
        return ok && user != null ? Optional.of(user) : Optional.empty();
    }

    /** A real hash of a value nobody knows, so a missing account costs the same work. */
    private static final String DUMMY = PasswordHash.hash(
            java.util.UUID.randomUUID().toString().toCharArray());
}
