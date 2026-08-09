package dev.relay.command;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves permission nodes for a player.
 *
 * <p>Spec &sect;5.7 and &sect;6.5 both call for one permission system rather than two, so
 * the nodes checked by in-game commands are the same strings a dashboard would check
 * before rendering a button. Storage is a flat map from config today; swapping in
 * database-backed roles later means replacing this class, not every call site.
 */
public final class Permissions {

    /** Key under {@code [permissions]} holding the nodes every player gets. */
    public static final String DEFAULT_KEY = "default";

    private final List<String> defaultNodes;
    private final Map<String, List<String>> byIdentifier;

    public Permissions(Map<String, List<String>> configured) {
        this.defaultNodes = configured.getOrDefault(DEFAULT_KEY, List.of());
        this.byIdentifier = configured;
    }

    /**
     * @param name the player's username, matched case-insensitively
     * @param uuid the player's UUID, which may also be used as a key
     */
    public boolean has(String name, UUID uuid, String node) {
        return matches(defaultNodes, node)
                || matches(byIdentifier.get(name.toLowerCase(Locale.ROOT)), node)
                || matches(byIdentifier.get(uuid.toString()), node);
    }

    private static boolean matches(List<String> granted, String node) {
        if (granted == null) {
            return false;
        }
        for (String entry : granted) {
            if (entry.equals("*") || entry.equals(node)) {
                return true;
            }
            // "relay.*" grants "relay.command.server"; a bare prefix does not.
            if (entry.endsWith(".*") && node.startsWith(entry.substring(0, entry.length() - 1))) {
                return true;
            }
        }
        return false;
    }
}
