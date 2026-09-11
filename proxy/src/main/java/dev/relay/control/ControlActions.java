package dev.relay.control;

import dev.relay.health.BackendHealth;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import dev.relay.proxy.ServerGroup;
import dev.relay.session.BackendConnector;
import dev.relay.store.History;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The things a companion may ask the proxy to <em>do</em>, as opposed to see.
 *
 * <p>A separate class from {@link ControlState} on purpose. That one says in its own header
 * that read-only is the boundary rather than a stage to grow out of, and quietly adding a
 * kick method to it would make that sentence a lie while leaving the shape of the thing
 * unchanged. Everything that can change the network is here, in one file, where the list is
 * the whole list.
 *
 * <h2>Who is allowed</h2>
 * Not decided here. A companion reached this channel by holding a token the proxy generated
 * at start and handed it through its own environment, which makes it infrastructure in the
 * same sense a backend is &mdash; and like a backend, it is trusted to have done its own
 * authorization. The dashboard checks the signed-in account against spec &sect;9.7's
 * permission nodes before it asks.
 *
 * <p>What the proxy does insist on is knowing <b>who</b>. Every action carries an actor,
 * every one is logged, and every one is written to {@code audit_event} &mdash; attempts
 * that were refused included, because "somebody tried to kick everyone and could not" is
 * the more interesting row.
 *
 * <h2>What is deliberately absent</h2>
 * Anything that edits configuration. {@code relay.toml} is the single source of truth for
 * proxy settings and something you keep in git; a dashboard that could rewrite it would
 * make the file a cache of a database nobody can read. Draining is the exception that
 * proves it: it changes a backend's <em>health</em>, which is runtime state the proxy
 * already owns and already changes on its own.
 */
public final class ControlActions {

    private static final Logger LOG = LoggerFactory.getLogger(ControlActions.class);

    /** How many players one action may move at once, so a mistyped name is not an outage. */
    static final int MAX_MOVED = 200;

    private final RelayProxy proxy;

    public ControlActions(RelayProxy proxy) {
        this.proxy = proxy;
    }

    /**
     * What happened, in the words the operator should be shown.
     *
     * <p>{@code ok} is about the request, not about the outcome being interesting: draining
     * a backend that was already draining is a refusal, because the operator asked for a
     * change and did not get one.
     */
    public record Result(boolean ok, String message) {
    }

    /**
     * Takes a backend out of rotation, or puts it back.
     *
     * <p>A group cannot be drained. Draining every member at once leaves the network with
     * nowhere to put anybody, which is the opposite of the point; {@code /drain} refuses it
     * for the same reason and this agrees with it rather than being a second opinion.
     */
    public Result drain(String actor, String serverName, boolean on) {
        RegisteredServer server = proxy.server(serverName).orElse(null);
        if (server == null) {
            return refuse(actor, "drain", serverName, proxy.group(serverName).isPresent()
                    ? "Drain members individually; draining the whole of '" + serverName
                            + "' would leave nowhere to send anyone"
                    : "There is no backend called '" + serverName + "'");
        }

        BackendHealth before = server.health();
        boolean draining = before.state() == BackendHealth.State.DRAINING;
        if (on == draining) {
            return refuse(actor, "drain", serverName, server.name()
                    + (draining ? " is already draining, with " + server.playerCount()
                            + " still on it" : " is not draining"));
        }

        // Back to UNKNOWN rather than to HEALTHY when undrained: the next check decides,
        // and claiming health Relay has not confirmed would be inventing it.
        server.setHealth(before.withState(on
                ? BackendHealth.State.DRAINING : BackendHealth.State.UNKNOWN));
        proxy.events().serverHealthChanged(server, before, server.health());

        int remaining = server.playerCount();
        String message = on
                ? server.name() + " is draining" + (remaining == 0
                        ? " and is already empty; safe to restart"
                        : "; " + players(remaining) + " will stay until they leave or are moved")
                : server.name() + " is taking new players again";
        return accept(actor, "drain", serverName, message);
    }

    /**
     * Moves one player to a backend or group.
     *
     * <p>Narrower than {@code /send}, which has a selector grammar built for typing. The
     * dashboard has a row per player and a name per backend, so the two gestures worth
     * having are this and {@link #evacuate}; inventing a second parser for selectors the
     * page cannot produce would be work nobody asked for.
     */
    public Result send(String actor, String playerName, String destination) {
        Optional<ConnectedPlayer> found = proxy.players().byName(playerName);
        if (found.isEmpty()) {
            return refuse(actor, "send", playerName, playerName + " is not online");
        }
        List<RegisteredServer> members = membersOf(destination);
        if (members.isEmpty()) {
            return refuse(actor, "send", playerName,
                    "There is no server or group called '" + destination + "'");
        }

        ConnectedPlayer player = found.get();
        ServerConnection current = player.connectedServer();
        if (current != null && members.contains(current.target())) {
            return refuse(actor, "send", playerName,
                    player.username() + " is already on " + destination);
        }
        move(player, destination);
        return accept(actor, "send", playerName,
                "Moving " + player.username() + " to " + destination);
    }

    /**
     * Moves everyone off a backend.
     *
     * <p>The action draining exists to be followed by. Draining stops new arrivals and
     * deliberately leaves the people already there; this is how an operator finishes the
     * job before a restart, and doing it one row at a time on a busy server is not a
     * workflow anybody would use.
     */
    public Result evacuate(String actor, String serverName, String destination) {
        RegisteredServer server = proxy.server(serverName).orElse(null);
        if (server == null) {
            return refuse(actor, "evacuate", serverName,
                    "There is no backend called '" + serverName + "'");
        }
        List<RegisteredServer> members = membersOf(destination);
        if (members.isEmpty()) {
            return refuse(actor, "evacuate", serverName,
                    "There is no server or group called '" + destination + "'");
        }
        if (members.contains(server)) {
            return refuse(actor, "evacuate", serverName,
                    "'" + destination + "' includes " + server.name() + ", so that would move "
                            + "them nowhere");
        }

        List<ConnectedPlayer> moving = new ArrayList<>(server.players());
        if (moving.isEmpty()) {
            return refuse(actor, "evacuate", serverName,
                    server.name() + " is already empty");
        }
        if (moving.size() > MAX_MOVED) {
            return refuse(actor, "evacuate", serverName,
                    server.name() + " has " + moving.size() + " players, over the " + MAX_MOVED
                            + " one action may move. Drain it and let it empty, or move them "
                            + "in batches.");
        }
        for (ConnectedPlayer player : moving) {
            move(player, destination);
        }
        return accept(actor, "evacuate", serverName,
                "Moving " + players(moving.size()) + " from " + server.name()
                        + " to " + destination);
    }

    /** Disconnects a player, with a reason they actually see. */
    public Result kick(String actor, String playerName, String reason) {
        Optional<ConnectedPlayer> found = proxy.players().byName(playerName);
        if (found.isEmpty()) {
            return refuse(actor, "kick", playerName, playerName + " is not online");
        }
        String shown = reason == null || reason.isBlank()
                ? "Disconnected by an administrator" : reason.trim();
        found.get().disconnect(Component.text(shown, NamedTextColor.RED));
        return accept(actor, "kick", playerName,
                "Disconnected " + found.get().username() + ": " + shown);
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * Resolves per player rather than once for a batch.
     *
     * <p>A group balances each choice, so moving forty people to {@code survival} spreads
     * them across its members instead of piling all forty onto whichever was emptiest when
     * the loop started.
     */
    private void move(ConnectedPlayer player, String destination) {
        RegisteredServer target = proxy.select(destination).orElse(null);
        if (target == null) {
            return;
        }
        player.sendMessage(Component.text("You are being moved to " + target.name(),
                NamedTextColor.GRAY));
        new BackendConnector(proxy, player).connect(target).whenComplete((result, error) -> {
            if (error != null || result == null || !result.successful()) {
                player.sendMessage(Component.text("Could not move you to " + target.name(),
                        NamedTextColor.RED));
            }
        });
    }

    /** Every backend a name covers: one server, or all of a group's members. */
    private List<RegisteredServer> membersOf(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        return proxy.group(name)
                .map(ServerGroup::members)
                .orElseGet(() -> proxy.server(name).map(List::<RegisteredServer>of).orElse(List.of()));
    }

    private Result accept(String actor, String action, String target, String message) {
        LOG.info("{} did {} on {}: {}", who(actor), action, target, message);
        record(actor, action, target, message);
        return new Result(true, message);
    }

    private Result refuse(String actor, String action, String target, String message) {
        LOG.info("{} was refused {} on {}: {}", who(actor), action, target, message);
        record(actor, action, target, "refused: " + message);
        return new Result(false, message);
    }

    private void record(String actor, String action, String target, String detail) {
        History history = proxy.history();
        if (history != null) {
            history.audit(who(actor), action, target, detail);
        }
    }

    /** A blank actor is a companion that did not say, which is worth seeing as such. */
    private static String who(String actor) {
        return actor == null || actor.isBlank() ? "unknown" : actor;
    }

    private static String players(int count) {
        return count + " player" + (count == 1 ? "" : "s");
    }
}
