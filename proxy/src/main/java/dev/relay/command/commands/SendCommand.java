package dev.relay.command.commands;

import dev.relay.command.Command;
import dev.relay.command.CommandSource;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import dev.relay.session.BackendConnector;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code /send <player|all|server:<name>> <target>} &mdash; move other players.
 *
 * <p>The bulk forms exist for the case they are actually needed: draining a backend
 * before restarting it.
 */
public final class SendCommand implements Command {

    private static final String SERVER_SELECTOR = "server:";

    private final RelayProxy proxy;

    public SendCommand(RelayProxy proxy) {
        this.proxy = proxy;
    }

    @Override
    public String name() {
        return "send";
    }

    @Override
    public String permission() {
        return "relay.command.send";
    }

    @Override
    public String usage() {
        return "/send <player|all|server:<name>> <target>";
    }

    @Override
    public void execute(CommandSource source, List<String> args) {
        if (args.size() < 2) {
            source.sendMessage(Component.text(usage(), NamedTextColor.RED));
            return;
        }

        Optional<RegisteredServer> target = proxy.server(args.get(1));
        if (target.isEmpty()) {
            source.sendMessage(Component.text("There is no server called '" + args.get(1) + "'",
                    NamedTextColor.RED));
            return;
        }
        RegisteredServer destination = target.get();

        List<ConnectedPlayer> selected = select(source, args.get(0), destination);
        if (selected == null) {
            return;
        }
        if (selected.isEmpty()) {
            source.sendMessage(Component.text("That selector matched nobody", NamedTextColor.RED));
            return;
        }

        for (ConnectedPlayer player : selected) {
            player.sendMessage(Component.text("You are being moved to " + destination.name(),
                    NamedTextColor.GRAY));
            new BackendConnector(proxy, player).connect(destination);
        }
        source.sendMessage(Component.text("Sending " + selected.size() + " player"
                + (selected.size() == 1 ? "" : "s") + " to " + destination.name(), NamedTextColor.GREEN));
    }

    /** @return the matched players, or {@code null} if the selector was invalid */
    private List<ConnectedPlayer> select(CommandSource source, String selector, RegisteredServer destination) {
        if (selector.equalsIgnoreCase("all")) {
            return excluding(proxy.players().snapshot(), destination);
        }

        if (selector.toLowerCase(java.util.Locale.ROOT).startsWith(SERVER_SELECTOR)) {
            String name = selector.substring(SERVER_SELECTOR.length());
            Optional<RegisteredServer> from = proxy.server(name);
            if (from.isEmpty()) {
                source.sendMessage(Component.text("There is no server called '" + name + "'",
                        NamedTextColor.RED));
                return null;
            }
            return excluding(List.copyOf(from.get().players()), destination);
        }

        Optional<ConnectedPlayer> player = proxy.players().byName(selector);
        if (player.isEmpty()) {
            source.sendMessage(Component.text(selector + " is not online", NamedTextColor.RED));
            return null;
        }
        return excluding(List.of(player.get()), destination);
    }

    /** Skips anyone already on the destination, so a bulk send is idempotent. */
    private static List<ConnectedPlayer> excluding(List<ConnectedPlayer> players, RegisteredServer destination) {
        List<ConnectedPlayer> filtered = new ArrayList<>(players.size());
        for (ConnectedPlayer player : players) {
            ServerConnection current = player.connectedServer();
            if (current == null || current.target() != destination) {
                filtered.add(player);
            }
        }
        return filtered;
    }
}
