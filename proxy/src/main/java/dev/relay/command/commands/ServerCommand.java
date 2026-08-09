package dev.relay.command.commands;

import dev.relay.command.Command;
import dev.relay.command.CommandSource;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.ConnectionResult;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
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

        Optional<RegisteredServer> target = proxy.server(args.get(0));
        if (target.isEmpty()) {
            source.sendMessage(Component.text("There is no server called '" + args.get(0) + "'",
                    NamedTextColor.RED));
            listServers(source, player);
            return;
        }

        RegisteredServer server = target.get();
        ServerConnection current = player.connectedServer();
        if (current != null && current.target() == server) {
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

    private void listServers(CommandSource source, ConnectedPlayer player) {
        ServerConnection current = player.connectedServer();
        StringJoiner joiner = new StringJoiner(", ");
        for (RegisteredServer server : proxy.servers()) {
            boolean here = current != null && current.target() == server;
            joiner.add(here ? server.name() + " (here)" : server.name());
        }
        source.sendMessage(Component.text("Servers: ", NamedTextColor.GOLD)
                .append(Component.text(joiner.toString(), NamedTextColor.WHITE)));
    }
}
