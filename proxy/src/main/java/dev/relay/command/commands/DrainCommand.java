package dev.relay.command.commands;

import dev.relay.command.Command;
import dev.relay.command.CommandSource;
import dev.relay.health.BackendHealth;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.StringJoiner;

/**
 * {@code /drain <server> [off]} &mdash; stop sending new players to a backend.
 *
 * <p>Spec &sect;6.5. The state and the routing that honours it already existed: a backend
 * marked {@code DRAINING} is skipped by {@link dev.relay.proxy.ServerGroup} when choosing
 * where to put someone, and a health check that finds it perfectly healthy will not put it
 * back into rotation, because an operator's decision outranks a measurement. What was
 * missing was any way to say so.
 *
 * <h2>What draining does not do</h2>
 * It does not move anyone. Spec &sect;6.5 is explicit that existing players stay connected,
 * and that is the useful behaviour: a restart that first ejects everyone it was trying to
 * protect has achieved nothing. The server empties as people leave or move on their own,
 * and Relay says so the moment it is empty &mdash; which is the moment an operator is
 * waiting for.
 *
 * <p>Emptying it deliberately is a separate decision with a separate command:
 * {@code /send server:<name> lobby}. Keeping them apart means "stop new arrivals" cannot
 * be typed and accidentally mean "move everyone now".
 */
public final class DrainCommand implements Command {

    private static final Logger LOG = LoggerFactory.getLogger(DrainCommand.class);

    private final RelayProxy proxy;

    public DrainCommand(RelayProxy proxy) {
        this.proxy = proxy;
    }

    @Override
    public String name() {
        return "drain";
    }

    @Override
    public String permission() {
        return "relay.command.drain";
    }

    @Override
    public String usage() {
        return "/drain [server] [off]";
    }

    @Override
    public void execute(CommandSource source, List<String> args) {
        if (args.isEmpty()) {
            report(source);
            return;
        }

        RegisteredServer server = proxy.server(args.get(0)).orElse(null);
        if (server == null) {
            // A group cannot be drained: draining every member at once would leave the
            // network with nowhere to put anybody, which is the opposite of the point.
            source.sendMessage(Component.text(
                    proxy.group(args.get(0)).isPresent()
                            ? "Drain members individually; draining the whole of '" + args.get(0)
                                    + "' would leave nowhere to send anyone"
                            : "There is no backend called '" + args.get(0) + "'",
                    NamedTextColor.RED));
            return;
        }

        boolean off = args.size() > 1 && args.get(1).equalsIgnoreCase("off");
        BackendHealth before = server.health();

        if (off) {
            if (before.state() != BackendHealth.State.DRAINING) {
                source.sendMessage(Component.text(server.name() + " is not draining",
                        NamedTextColor.RED));
                return;
            }
            // Back to UNKNOWN rather than to HEALTHY: the next check decides, and
            // claiming health Relay has not confirmed would be inventing it.
            server.setHealth(before.withState(BackendHealth.State.UNKNOWN));
            proxy.events().serverHealthChanged(server, before, server.health());
            source.sendMessage(Component.text(server.name() + " is taking new players again",
                    NamedTextColor.GREEN));
            return;
        }

        if (before.state() == BackendHealth.State.DRAINING) {
            source.sendMessage(Component.text(server.name() + " is already draining, with "
                    + server.playerCount() + " still on it", NamedTextColor.RED));
            return;
        }

        server.setHealth(before.withState(BackendHealth.State.DRAINING));
        proxy.events().serverHealthChanged(server, before, server.health());

        int remaining = server.playerCount();
        source.sendMessage(Component.text(server.name() + " is draining", NamedTextColor.GOLD)
                .append(Component.text(remaining == 0
                        ? " and is already empty; safe to restart"
                        : "; " + remaining + " player" + (remaining == 1 ? "" : "s")
                                + " will stay until they leave or are moved",
                        NamedTextColor.GRAY)));
        if (remaining > 0) {
            source.sendMessage(Component.text("Move them with /send server:" + server.name()
                    + " <destination>", NamedTextColor.DARK_GRAY));
        }
    }

    /** Lists what is draining, so the state is discoverable without the dashboard. */
    private void report(CommandSource source) {
        StringJoiner draining = new StringJoiner(", ");
        for (RegisteredServer server : proxy.servers()) {
            if (server.health().state() == BackendHealth.State.DRAINING) {
                draining.add(server.name() + " (" + server.playerCount() + ")");
            }
        }
        if (draining.length() == 0) {
            source.sendMessage(Component.text("Nothing is draining. " + usage(), NamedTextColor.GRAY));
            return;
        }
        source.sendMessage(Component.text("Draining: ", NamedTextColor.GOLD)
                .append(Component.text(draining.toString(), NamedTextColor.WHITE)));
    }

    /**
     * Announces a drained backend the moment it empties.
     *
     * <p>This is the whole point of draining, and it is not something an operator should
     * have to poll for. Wired to player movement rather than to the health check so it
     * lands when the last player actually leaves, not up to an interval later.
     */
    public static void announceIfDrained(RegisteredServer server) {
        if (server == null
                || server.health().state() != BackendHealth.State.DRAINING
                || server.playerCount() > 0) {
            return;
        }
        LOG.info("{} has drained: no players remain. It is safe to restart.", server.name());
    }
}
