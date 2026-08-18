package dev.relay.debug;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * A backend-side companion for diagnosing connections that die without explanation.
 *
 * <p>Paper reports a channel that simply vanishes as "lost connection: Disconnected" and
 * logs the underlying cause at DEBUG, where it is invisible by default. Worse, when the
 * pipeline closes a channel before the exception handler runs, nothing is logged at all.
 * That is the gap this plugin fills: it installs a handler on each player's Netty
 * pipeline that records who closed the connection, with the stack trace of the call.
 *
 * <p>Purely diagnostic --- it observes and reports, and changes nothing about how the
 * server handles a connection.
 */
public final class RelayDebugPlugin extends JavaPlugin {

    private ConnectionInspector inspector;
    private BackendApiProbe probe;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        boolean logPackets = getConfig().getBoolean("log-packets", false);

        inspector = new ConnectionInspector(this, logPackets);
        probe = new BackendApiProbe(this);
        probe.register();

        getServer().getPluginManager().registerEvents(
                new DisconnectListener(this, inspector, probe, getConfig().getBoolean("probe-on-join", true)), this);
        getCommand("relaytest").setExecutor(new RelayTestCommand(this, probe));

        getLogger().info("Watching player connections for unexplained closes.");
        if (logPackets) {
            getLogger().warning("log-packets is on: this is extremely verbose, for short "
                    + "diagnostic sessions only.");
        }

        // Anyone already online when the plugin is reloaded would otherwise be missed.
        for (Player player : getServer().getOnlinePlayers()) {
            inspector.attach(player);
        }
    }

    @Override
    public void onDisable() {
        if (inspector != null) {
            inspector.detachAll();
        }
    }

    void report(String message) {
        getLogger().warning(message);
    }

    void report(String message, Throwable cause) {
        getLogger().log(Level.WARNING, message, cause);
    }

    void detail(String message) {
        getLogger().info(message);
    }
}
