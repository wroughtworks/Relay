package dev.relay.command.commands;

import dev.relay.command.Command;
import dev.relay.command.CommandSource;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.NetworkRoute;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
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

        // The route, because "is on survival-02" does not answer the question that
        // usually follows it. With forced hosts and groups, the path is the reason they
        // are there, and it is otherwise not visible from anywhere in game.
        NetworkRoute route = NetworkRoute.of(player, proxy);
        TextComponent.Builder path = Component.text();
        for (int i = 0; i < route.hops().size(); i++) {
            if (i > 0) {
                path.append(Component.text(" -> ", NamedTextColor.DARK_GRAY));
            }
            path.append(Component.text(route.hops().get(i).name(), colourOf(route.hops().get(i).kind())));
        }
        source.sendMessage(Component.text("  route ", NamedTextColor.GRAY).append(path.build()));

        if (route.virtualHost() != null) {
            source.sendMessage(Component.text("  via   ", NamedTextColor.GRAY)
                    .append(Component.text(route.virtualHost(), NamedTextColor.WHITE)));
        }
        source.sendMessage(Component.text("  id    ", NamedTextColor.GRAY)
                .append(Component.text(route.routeId(), NamedTextColor.DARK_GRAY)));
    }

    /** Distinct colours per hop kind, so a route reads without labels on each hop. */
    private static NamedTextColor colourOf(NetworkRoute.Kind kind) {
        return switch (kind) {
            case EDGE -> NamedTextColor.GRAY;
            case PROXY -> NamedTextColor.AQUA;
            case POOL -> NamedTextColor.GREEN;
            case BACKEND -> NamedTextColor.GOLD;
        };
    }
}
