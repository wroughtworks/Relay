package dev.relay.paper;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * The backend-side half of Relay.
 *
 * <p>Two jobs. It exposes the proxy's {@link RelayApi backend API} to this server, so
 * plugins can move players, query the network and pass messages between servers. And it
 * reports why connections close: Paper describes a channel that simply vanishes as "lost
 * connection: Disconnected", logs the cause at DEBUG where it is invisible, and logs
 * nothing at all when the pipeline closes a channel before its exception handler runs.
 * A handler on each player's Netty pipeline records who closed the connection, with the
 * stack trace of the call.
 *
 * <p>The diagnostic half only observes; it changes nothing about how the server handles
 * a connection.
 */
public final class RelayPlugin extends JavaPlugin {

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
        getCommand("relay").setExecutor(new RelayCommand(this, probe));

        // The proxy's own commands, forwarded verbatim. Registering them here is what
        // gives them tab completion and a place in the client's command tree; chat
        // interception alone leaves them looking like unknown commands.
        ProxyCommand proxyCommand = new ProxyCommand(this, probe);
        for (String name : new String[]{"server", "glist", "find", "send"}) {
            getCommand(name).setExecutor(proxyCommand);
            getCommand(name).setTabCompleter(proxyCommand);
        }

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
