package dev.relay.command.commands;

import dev.relay.command.Command;
import dev.relay.command.CommandSource;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Optional;

/** {@code /find <player>} &mdash; which backend a player is on. */
public final class FindCommand implements Command {

    private final RelayProxy proxy;

    public FindCommand(RelayProxy proxy) {
        this.proxy = proxy;
    }

    @Override
    public String name() {
        return "find";
    }

    @Override
    public List<String> aliases() {
        return List.of("locate");
    }

    @Override
    public String permission() {
        return "relay.command.find";
    }

    @Override
    public String usage() {
        return "/find <player>";
    }

    @Override
    public void execute(CommandSource source, List<String> args) {
        if (args.isEmpty()) {
            source.sendMessage(Component.text(usage(), NamedTextColor.RED));
            return;
        }

        Optional<ConnectedPlayer> found = proxy.players().byName(args.get(0));
        if (found.isEmpty()) {
            source.sendMessage(Component.text(args.get(0) + " is not online", NamedTextColor.RED));
            return;
        }

        ConnectedPlayer player = found.get();
        ServerConnection server = player.connectedServer();
        if (server == null) {
            source.sendMessage(Component.text(player.username() + " is connecting to a server",
                    NamedTextColor.GRAY));
            return;
        }
        source.sendMessage(Component.text(player.username(), NamedTextColor.WHITE)
                .append(Component.text(" is on ", NamedTextColor.GRAY))
                .append(Component.text(server.target().name(), NamedTextColor.GOLD)));
    }
}
