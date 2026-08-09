package dev.relay.proxy;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everyone currently connected, indexed by UUID and by lowercased name.
 *
 * <p>Two indexes rather than a scan because {@code /find} and {@code /send} both look up
 * by name, and the online count is read on every server-list ping.
 */
public final class PlayerRegistry {

    private final Map<UUID, ConnectedPlayer> byUuid = new ConcurrentHashMap<>();
    private final Map<String, ConnectedPlayer> byName = new ConcurrentHashMap<>();

    /**
     * Adds a player, refusing if the same account is already online.
     *
     * @return {@code false} if that UUID or name is already taken, in which case the
     *         caller should kick the newcomer rather than displace the incumbent
     */
    public boolean add(ConnectedPlayer player) {
        String key = player.username().toLowerCase(Locale.ROOT);
        if (byUuid.putIfAbsent(player.uuid(), player) != null) {
            return false;
        }
        if (byName.putIfAbsent(key, player) != null) {
            byUuid.remove(player.uuid(), player);
            return false;
        }
        return true;
    }

    public void remove(ConnectedPlayer player) {
        byUuid.remove(player.uuid(), player);
        byName.remove(player.username().toLowerCase(Locale.ROOT), player);
    }

    public Optional<ConnectedPlayer> byName(String name) {
        return Optional.ofNullable(byName.get(name.toLowerCase(Locale.ROOT)));
    }

    public Optional<ConnectedPlayer> byUuid(UUID uuid) {
        return Optional.ofNullable(byUuid.get(uuid));
    }

    public Collection<ConnectedPlayer> all() {
        return Collections.unmodifiableCollection(byUuid.values());
    }

    /** A snapshot safe to iterate while players connect and disconnect. */
    public List<ConnectedPlayer> snapshot() {
        return List.copyOf(byUuid.values());
    }

    public int count() {
        return byUuid.size();
    }
}
