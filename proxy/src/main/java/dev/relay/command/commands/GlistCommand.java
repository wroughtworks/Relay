package dev.relay.command.commands;

import dev.relay.command.Command;
import dev.relay.command.CommandSource;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.StringJoiner;

/** {@code /glist} &mdash; who is online, grouped by backend. */
public final class GlistCommand implements Command {

    private final RelayProxy proxy;

    public GlistCommand(RelayProxy proxy) {
        this.proxy = proxy;
    }

    @Override
    public String name() {
        return "glist";
    }

    @Override
    public String permission() {
        return "relay.command.glist";
    }

    @Override
    public String usage() {
        return "/glist";
    }

    @Override
    public void execute(CommandSource source, List<String> args) {
        int total = proxy.players().count();

        for (RegisteredServer server : proxy.servers()) {
            List<ConnectedPlayer> players = List.copyOf(server.players());
            if (players.isEmpty()) {
                continue;
            }
            StringJoiner names = new StringJoiner(", ");
            for (ConnectedPlayer player : players) {
                names.add(player.username());
            }
            source.sendMessage(Component.text("[" + server.name() + "] ", NamedTextColor.GOLD)
                    .append(Component.text("(" + players.size() + ") ", NamedTextColor.GRAY))
                    .append(Component.text(names.toString(), NamedTextColor.WHITE)));
        }

        source.sendMessage(Component.text(total + " player" + (total == 1 ? "" : "s") + " online",
                NamedTextColor.GREEN));
    }
}
