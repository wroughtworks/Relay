package dev.relay.command.commands;

import dev.relay.command.Command;
import dev.relay.command.CommandSource;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerGroup;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.StringJoiner;

/** {@code /glist} &mdash; who is online, by group and backend. */
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

        for (ServerGroup group : proxy.groups()) {
            if (group.playerCount() == 0) {
                continue;
            }
            // The group total first, then each member. Whether a group is balanced is
            // the question this command gets asked about a group, and it is answerable
            // only by seeing the members side by side under their shared total.
            source.sendMessage(Component.text(group.name() + " ", NamedTextColor.GOLD)
                    .append(Component.text("(" + group.playerCount() + ")", NamedTextColor.GRAY)));
            for (RegisteredServer member : group.members()) {
                listServer(source, member, "  ");
            }
        }

        for (RegisteredServer server : proxy.servers()) {
            if (proxy.groups().stream().anyMatch(group -> group.members().contains(server))) {
                continue;
            }
            listServer(source, server, "");
        }

        source.sendMessage(Component.text(total + " player" + (total == 1 ? "" : "s") + " online",
                NamedTextColor.GREEN));
    }

    private static void listServer(CommandSource source, RegisteredServer server, String indent) {
        List<ConnectedPlayer> players = List.copyOf(server.players());
        if (players.isEmpty()) {
            return;
        }
        StringJoiner names = new StringJoiner(", ");
        for (ConnectedPlayer player : players) {
            names.add(player.username());
        }
        source.sendMessage(Component.text(indent + "[" + server.name() + "] ", NamedTextColor.GOLD)
                .append(Component.text("(" + players.size() + ") ", NamedTextColor.GRAY))
                .append(Component.text(names.toString(), NamedTextColor.WHITE)));
    }
}
