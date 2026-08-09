package dev.relay.debug;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * The API-level half of the diagnosis.
 *
 * <p>A kick raised through the Bukkit API arrives here with its reason intact, which
 * covers the cases the pipeline handler cannot explain on its own: a plugin kicking the
 * player, or the server refusing them for a reason it does not otherwise print. Between
 * the two, an unexplained disconnect narrows to either a named kick or a specific line of
 * server code.
 */
final class DisconnectListener implements Listener {

    private final RelayDebugPlugin plugin;
    private final ConnectionInspector inspector;

    DisconnectListener(RelayDebugPlugin plugin, ConnectionInspector inspector) {
        this.plugin = plugin;
        this.inspector = inspector;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        inspector.attach(event.getPlayer());
    }

    /** Lowest priority so the reason is seen before another plugin can change it. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onKick(PlayerKickEvent event) {
        plugin.report(String.format("%s is being KICKED. cause=%s reason=%s",
                event.getPlayer().getName(),
                event.getCause(),
                plainText(event.reason())));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.detail(event.getPlayer().getName() + " left (" + event.getReason() + ")");
        inspector.detach(event.getPlayer());
    }

    /** Flattens an Adventure component without assuming a serializer is on the classpath. */
    private static String plainText(Object component) {
        if (component == null) {
            return "(none)";
        }
        try {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize((net.kyori.adventure.text.Component) component);
        } catch (Throwable ignored) {
            return component.toString();
        }
    }
}
