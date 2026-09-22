package dev.relay.paper;

import org.bukkit.plugin.Plugin;

import java.util.logging.Level;

/**
 * A {@link Reporter} over any plugin's own logger.
 *
 * <p>{@link RelayPlugin} is already a {@code Reporter}, but a {@link PacketFactory} is
 * built by somebody else's plugin and should complain in that plugin's name &mdash; an
 * operator reading "registries disagree" wants to know which plugin's registries, and
 * Relay's logger would name the wrong one.
 */
final class PluginReporter implements Reporter {

    private final Plugin plugin;

    PluginReporter(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void report(String message) {
        plugin.getLogger().warning(message);
    }

    @Override
    public void report(String message, Throwable cause) {
        plugin.getLogger().log(Level.WARNING, message, cause);
    }

    @Override
    public void detail(String message) {
        plugin.getLogger().fine(message);
    }
}
