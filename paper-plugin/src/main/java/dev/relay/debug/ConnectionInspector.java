package dev.relay.debug;

import io.netty.channel.Channel;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds each player's Netty channel and installs the watching handler on it.
 *
 * <p>The channel is reached by searching the server's player object for a field of type
 * {@link Channel}, rather than by following a named path such as
 * {@code ServerPlayer.connection.connection.channel}. Those names are remapped between
 * Minecraft versions and differ between Spigot-mapped and Mojang-mapped Paper builds, so
 * a name-based path breaks on upgrade. A type-based search does not care what anything is
 * called.
 */
final class ConnectionInspector {

    /** How deep to search before giving up; the channel sits two or three hops in. */
    private static final int MAX_DEPTH = 4;

    private static final String HANDLER_NAME = "relay-debug";

    private final RelayDebugPlugin plugin;
    private final boolean logPackets;
    private final Map<UUID, Channel> attached = new ConcurrentHashMap<>();

    ConnectionInspector(RelayDebugPlugin plugin, boolean logPackets) {
        this.plugin = plugin;
        this.logPackets = logPackets;
    }

    void attach(Player player) {
        if (attached.containsKey(player.getUniqueId())) {
            return;
        }
        Channel channel = findChannel(player);
        if (channel == null) {
            plugin.report("Could not find the Netty channel for " + player.getName()
                    + "; close diagnostics are unavailable. This usually means the server "
                    + "internals moved in a newer Minecraft version.");
            return;
        }

        ConnectionWatcher watcher = new ConnectionWatcher(plugin, player.getName(), logPackets);
        // In front of everything, so a close is seen no matter which handler triggers it.
        channel.pipeline().addFirst(HANDLER_NAME, watcher);
        attached.put(player.getUniqueId(), channel);
        plugin.detail("Watching " + player.getName() + "'s connection (" + channel.remoteAddress() + ")");
    }

    void detach(Player player) {
        Channel channel = attached.remove(player.getUniqueId());
        removeHandler(channel);
    }

    void detachAll() {
        for (Channel channel : attached.values()) {
            removeHandler(channel);
        }
        attached.clear();
    }

    private void removeHandler(Channel channel) {
        if (channel == null) {
            return;
        }
        try {
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }
        } catch (RuntimeException ignored) {
            // The channel closed first, which takes its pipeline with it.
        }
    }

    /** Breadth-first search of the player object's fields for a {@link Channel}. */
    private Channel findChannel(Player player) {
        Object handle = invokeGetHandle(player);
        if (handle == null) {
            return null;
        }

        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{handle, 0});
        Set<Object> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(handle);

        while (!queue.isEmpty()) {
            Object[] entry = queue.poll();
            Object current = entry[0];
            int depth = (Integer) entry[1];
            if (depth > MAX_DEPTH) {
                continue;
            }

            for (Field field : allFields(current.getClass())) {
                if (field.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(current);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    continue;
                }
                if (value == null) {
                    continue;
                }
                if (value instanceof Channel channel) {
                    return channel;
                }
                // Only follow server-internal objects; walking into collections or JDK
                // types would explode the search for nothing.
                String packageName = value.getClass().getName();
                if (seen.add(value)
                        && (packageName.startsWith("net.minecraft") || packageName.startsWith("org.bukkit")
                            || packageName.startsWith("io.papermc") || packageName.startsWith("com.destroystokyo"))) {
                    queue.add(new Object[]{value, depth + 1});
                }
            }
        }
        return null;
    }

    private Object invokeGetHandle(Player player) {
        try {
            Method getHandle = player.getClass().getMethod("getHandle");
            getHandle.setAccessible(true);
            return getHandle.invoke(player);
        } catch (ReflectiveOperationException e) {
            plugin.report("CraftPlayer.getHandle() is not available", e);
            return null;
        }
    }

    private static List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            fields.addAll(List.of(current.getDeclaredFields()));
        }
        return fields;
    }
}
