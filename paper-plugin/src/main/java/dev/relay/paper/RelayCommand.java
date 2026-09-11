package dev.relay.paper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@code /relay} — drives the backend API by hand.
 *
 * <p>The read-only requests run automatically on join. This exists for the ones that
 * change something, which should never fire on their own: moving a player, and sending a
 * cross-server payload.
 */
final class RelayCommand implements CommandExecutor, TabCompleter {

    private final RelayPlugin plugin;
    private final BackendApiProbe probe;
    private final PacketProbe packets;

    RelayCommand(RelayPlugin plugin, BackendApiProbe probe, PacketProbe packets) {
        this.plugin = plugin;
        this.probe = probe;
        this.packets = packets;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // "packet" is deliberately allowed from the console. The others put the sender's
        // own connection on the wire, so they need a player; a packet factory picks its
        // own carrier, which means this one can be driven over RCON -- and being able to
        // test cross-server messaging without logging in is most of its value.
        if (args.length > 0 && args[0].equalsIgnoreCase("packet")) {
            return packet(sender, args);
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command needs a player: every API request travels on a "
                    + "player's connection.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("/" + label + " probe            re-run the read-only checks");
            sender.sendMessage("/" + label + " connect <server> move yourself via the proxy");
            sender.sendMessage("/" + label + " forward <text>   send text to every other server");
            sender.sendMessage("/" + label + " packet <server> [note]  typed round trip to a backend");
            return true;
        }

        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "probe" -> {
                probe.probe(player);
                sender.sendMessage("Probe sent; results are in the server console.");
            }
            case "connect" -> {
                if (args.length < 2) {
                    sender.sendMessage("Which server?");
                    return true;
                }
                sender.sendMessage("Asking the proxy to move you to " + args[1] + "...");
                plugin.report(player.getName() + " requested a move to " + args[1]
                        + " through the backend API");
                probe.api().connect(player, args[1]);
            }
            case "forward" -> {
                if (args.length < 2) {
                    sender.sendMessage("What text?");
                    return true;
                }
                String text = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
                probe.api().forward(player, "ALL", "RelayTest",
                        text.getBytes(StandardCharsets.UTF_8));
                sender.sendMessage("Forwarded to every other server that has a player on it.");
            }
            default -> sender.sendMessage("Unknown option '" + args[0] + "'");
        }
        return true;
    }

    private boolean packet(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Which server? Both ends need the Relay plugin.");
            return true;
        }
        packets.ping(args[1], args.length > 2
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                : sender.getName());
        sender.sendMessage("Ping sent; the round trip is reported in both consoles.");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("probe", "connect", "forward", "packet");
        }
        // Both "connect" and "packet" want somewhere to send to, and the proxy has
        // already told this server what those are.
        boolean wantsDestination = args.length == 2
                && (args[0].equalsIgnoreCase("connect") || args[0].equalsIgnoreCase("packet"));
        return wantsDestination ? probe.knownServers() : List.of();
    }
}
