package dev.relay.command.commands;

import dev.relay.command.Command;
import dev.relay.command.CommandSource;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import dev.relay.proxy.ServerGroup;
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
        return "/send <player|all|server:<name>> <server|group>";
    }

    @Override
    public void execute(CommandSource source, List<String> args) {
        if (args.size() < 2) {
            source.sendMessage(Component.text(usage(), NamedTextColor.RED));
            return;
        }

        String requested = args.get(1);
        List<RegisteredServer> destinations = members(requested);
        if (destinations.isEmpty()) {
            source.sendMessage(Component.text("There is no server or group called '" + requested + "'",
                    NamedTextColor.RED));
            return;
        }

        List<ConnectedPlayer> selected = select(source, args.get(0), destinations);
        if (selected == null) {
            return;
        }
        if (selected.isEmpty()) {
            source.sendMessage(Component.text("That selector matched nobody", NamedTextColor.RED));
            return;
        }

        for (ConnectedPlayer player : selected) {
            // Resolved per player, not once for the batch. A group balances each choice,
            // so sending forty people to "survival" spreads them across its members
            // rather than piling all forty onto whichever was emptiest at the start.
            RegisteredServer destination = proxy.select(requested).orElseThrow();
            player.sendMessage(Component.text("You are being moved to " + destination.name(),
                    NamedTextColor.GRAY));
            new BackendConnector(proxy, player).connect(destination);
        }
        source.sendMessage(Component.text("Sending " + selected.size() + " player"
                + (selected.size() == 1 ? "" : "s") + " to " + requested, NamedTextColor.GREEN));
    }

    /**
     * Every backend a name covers: one server, or all of a group's members.
     *
     * <p>Not the balanced pick. Both uses here are about the destination as a whole —
     * who counts as already there, and who a {@code server:} selector matches — and for
     * those, a group means all of it.
     */
    private List<RegisteredServer> members(String name) {
        return proxy.group(name)
                .map(ServerGroup::members)
                .orElseGet(() -> proxy.server(name).map(List::of).orElse(List.of()));
    }

    /** @return the matched players, or {@code null} if the selector was invalid */
    private List<ConnectedPlayer> select(CommandSource source, String selector,
                                         List<RegisteredServer> destinations) {
        if (selector.equalsIgnoreCase("all")) {
            return excluding(proxy.players().snapshot(), destinations);
        }

        if (selector.toLowerCase(java.util.Locale.ROOT).startsWith(SERVER_SELECTOR)) {
            String name = selector.substring(SERVER_SELECTOR.length());
            List<RegisteredServer> from = members(name);
            if (from.isEmpty()) {
                source.sendMessage(Component.text("There is no server or group called '" + name + "'",
                        NamedTextColor.RED));
                return null;
            }
            List<ConnectedPlayer> found = new ArrayList<>();
            for (RegisteredServer server : from) {
                found.addAll(server.players());
            }
            return excluding(found, destinations);
        }

        Optional<ConnectedPlayer> player = proxy.players().byName(selector);
        if (player.isEmpty()) {
            source.sendMessage(Component.text(selector + " is not online", NamedTextColor.RED));
            return null;
        }
        return excluding(List.of(player.get()), destinations);
    }

    /**
     * Skips anyone already at the destination, so a bulk send is idempotent.
     *
     * <p>For a group that means any member. Someone on {@code survival-02} has already
     * arrived at {@code survival}, and shunting them to {@code survival-01} would be a
     * round trip through configuration that changes nothing they can see.
     */
    private static List<ConnectedPlayer> excluding(List<ConnectedPlayer> players,
                                                   List<RegisteredServer> destinations) {
        List<ConnectedPlayer> filtered = new ArrayList<>(players.size());
        for (ConnectedPlayer player : players) {
            ServerConnection current = player.connectedServer();
            if (current == null || !destinations.contains(current.target())) {
                filtered.add(player);
            }
        }
        return filtered;
    }
}
