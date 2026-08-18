package dev.relay.command.commands;

import dev.relay.command.Command;
import dev.relay.command.CommandSource;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.ConnectionResult;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import dev.relay.proxy.ServerGroup;
import dev.relay.session.BackendConnector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * {@code /server [name]} &mdash; move to another backend, or list what is available.
 *
 * <p>Connection handoff only. Spec &sect;5.4 draws the line here deliberately: carrying
 * inventory or game state between backends is a backend concern, and a proxy that starts
 * doing it stops being a proxy.
 */
public final class ServerCommand implements Command {

    private final RelayProxy proxy;

    public ServerCommand(RelayProxy proxy) {
        this.proxy = proxy;
    }

    @Override
    public String name() {
        return "server";
    }

    @Override
    public String permission() {
        return "relay.command.server";
    }

    @Override
    public String usage() {
        return "/server [name]";
    }

    @Override
    public void execute(CommandSource source, List<String> args) {
        ConnectedPlayer player = source.asPlayer();
        if (player == null) {
            source.sendMessage(Component.text("Only a player can switch servers", NamedTextColor.RED));
            return;
        }

        if (args.isEmpty()) {
            listServers(source, player);
            return;
        }

        String requested = args.get(0);
        Optional<RegisteredServer> target = proxy.select(requested);
        if (target.isEmpty()) {
            source.sendMessage(Component.text("There is no server or group called '" + requested + "'",
                    NamedTextColor.RED));
            listServers(source, player);
            return;
        }

        RegisteredServer server = target.get();
        ServerConnection current = player.connectedServer();
        RegisteredServer here = current == null ? null : current.target();

        // A group is one destination as far as the player is concerned, so being on any
        // of its members already counts as being there. Moving them to a sibling would
        // be a pointless round trip through configuration for no change of scenery.
        Optional<ServerGroup> group = proxy.group(requested);
        if (group.isPresent() && here != null && group.get().members().contains(here)) {
            source.sendMessage(Component.text("You are already on " + group.get().name()
                    + ", on " + here.name(), NamedTextColor.RED));
            return;
        }
        if (here == server) {
            source.sendMessage(Component.text("You are already on " + server.name(), NamedTextColor.RED));
            return;
        }

        source.sendMessage(Component.text("Connecting to " + server.name() + "...", NamedTextColor.GRAY));
        new BackendConnector(proxy, player).connect(server).whenComplete((result, error) ->
                player.connection().channel().eventLoop().execute(() -> report(player, server, result, error)));
    }

    private void report(ConnectedPlayer player, RegisteredServer server, ConnectionResult result, Throwable error) {
        if (!player.isActive()) {
            return;
        }
        if (error != null) {
            player.sendMessage(Component.text("Could not connect to " + server.name(), NamedTextColor.RED));
            return;
        }
        switch (result) {
            case ConnectionResult.Success ignored ->
                    player.sendMessage(Component.text("You are now on " + server.name(), NamedTextColor.GREEN));
            case ConnectionResult.Rejected rejected -> {
                player.sendMessage(Component.text(server.name() + " refused the connection:", NamedTextColor.RED));
                player.sendMessage(rejected.reason());
            }
            case ConnectionResult.Unreachable ignored ->
                    player.sendMessage(Component.text(server.name() + " is not reachable right now",
                            NamedTextColor.RED));
        }
    }

    /**
     * Lists what can be typed, with grouped backends shown under their group.
     *
     * <p>Members are still listed individually. A player normally wants the group and
     * lets Relay choose, but naming a member directly is how you rejoin the server your
     * base is on, so hiding them would take away the only way to ask for it.
     */
    private void listServers(CommandSource source, ConnectedPlayer player) {
        ServerConnection current = player.connectedServer();
        RegisteredServer here = current == null ? null : current.target();

        for (ServerGroup group : proxy.groups()) {
            StringJoiner members = new StringJoiner(", ");
            for (RegisteredServer member : group.members()) {
                members.add(member == here ? member.name() + " (here)" : member.name());
            }
            source.sendMessage(Component.text(group.name() + ": ", NamedTextColor.GOLD)
                    .append(Component.text(members.toString(), NamedTextColor.WHITE))
                    .append(Component.text(" (" + group.playerCount() + " online)", NamedTextColor.GRAY)));
        }

        StringJoiner ungrouped = new StringJoiner(", ");
        for (RegisteredServer server : proxy.servers()) {
            if (proxy.groups().stream().anyMatch(group -> group.members().contains(server))) {
                continue;
            }
            ungrouped.add(server == here ? server.name() + " (here)" : server.name());
        }
        if (ungrouped.length() > 0) {
            source.sendMessage(Component.text("Servers: ", NamedTextColor.GOLD)
                    .append(Component.text(ungrouped.toString(), NamedTextColor.WHITE)));
        }
    }
}
