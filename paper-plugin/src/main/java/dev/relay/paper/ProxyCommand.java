package dev.relay.paper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Forwards a proxy command typed on this server to the proxy.
 *
 * <p>Registering these as real server commands buys what chat interception cannot: they
 * appear in the client's command tree, so they tab-complete and are not struck through as
 * unknown, and Bukkit's own dispatch handles quoting and aliases. Everything after that
 * is the proxy's business &mdash; the line is sent verbatim and the proxy decides, using
 * the same permission nodes as a typed command, whether to act on it.
 *
 * <p>Deliberately thin. Adding a command to the proxy means adding a line to plugin.yml
 * here, not new code on either side.
 */
final class ProxyCommand implements CommandExecutor, TabCompleter {

    private final RelayPlugin plugin;
    private final BackendApiProbe probe;

    ProxyCommand(RelayPlugin plugin, BackendApiProbe probe) {
        this.plugin = plugin;
        this.probe = probe;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only a player can use proxy commands: they run as that player.");
            return true;
        }

        // The command name as the proxy knows it, not the alias that was typed, so
        // /glist and any alias of it both reach the same proxy command.
        StringBuilder line = new StringBuilder(command.getName());
        for (String arg : args) {
            line.append(' ').append(arg);
        }

        probe.api().runCommand(player, line.toString());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        // Completed from the server list the proxy reported on join. Only the first
        // argument of a server-taking command is worth completing; the rest are player
        // names, which Bukkit already completes from the online list.
        if (args.length != 1) {
            return List.of();
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!name.equals("server") && !name.equals("send")) {
            return List.of();
        }

        String prefix = args[0].toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String server : probe.knownServers()) {
            if (server.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(server);
            }
        }
        return matches;
    }
}
