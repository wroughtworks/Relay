package dev.relay.session;

import dev.relay.config.ForwardingMode;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.ConnectionResult;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Decides which backend a player lands on, and what happens when that fails.
 *
 * <p>Fallback applies only while the player is still being connected. Once a backend has
 * begun configuring the client there is no way back &mdash; the client has already taken
 * on that backend's registries &mdash; so a later failure is a disconnect, not a retry.
 */
public final class BackendConnector {

    private static final Logger LOG = LoggerFactory.getLogger(BackendConnector.class);

    private final RelayProxy proxy;
    private final ConnectedPlayer player;

    public BackendConnector(RelayProxy proxy, ConnectedPlayer player) {
        this.proxy = proxy;
        this.player = player;
    }

    /** Walks the configured try order, or the forced host for the address the player used. */
    public void connectToInitialServer() {
        List<String> candidates = proxy.config().initialCandidates(player.virtualHost());
        tryCandidate(candidates, 0, null);
    }

    private void tryCandidate(List<String> candidates, int index, Component lastReason) {
        if (!player.isActive()) {
            return;
        }
        if (index >= candidates.size()) {
            player.disconnect(lastReason != null
                    ? lastReason
                    : Component.text("No available server to connect you to", NamedTextColor.RED));
            return;
        }

        String name = candidates.get(index);
        RegisteredServer server = proxy.server(name).orElse(null);
        if (server == null) {
            LOG.warn("Fallback list names unknown backend '{}'", name);
            tryCandidate(candidates, index + 1, lastReason);
            return;
        }

        connect(server).whenComplete((result, error) -> player.connection().channel().eventLoop().execute(() -> {
            if (error != null) {
                LOG.error("Error connecting {} to {}", player.username(), server.name(), error);
                tryCandidate(candidates, index + 1, Component.text("Could not connect you to a server",
                        NamedTextColor.RED));
                return;
            }
            if (result.successful()) {
                return;
            }
            logFailure(server, result);
            tryCandidate(candidates, index + 1, describe(result));
        }));
    }

    /**
     * Starts a switch to {@code target}.
     *
     * <p>Refuses if a switch is already under way: two overlapping switches would both
     * try to drive the same client through configuration state.
     */
    public CompletableFuture<ConnectionResult> connect(RegisteredServer target) {
        ServerConnection existing = player.connectedServer();
        if (existing != null && existing.target() == target) {
            return CompletableFuture.completedFuture(
                    new ConnectionResult.Rejected(target, Component.text("You are already on that server",
                            NamedTextColor.RED)));
        }
        return proxy.connect(player, target);
    }

    /** Releases the player's backend connections when their own socket drops. */
    public void handlePlayerDisconnect() {
        ServerConnection current = player.connectedServer();
        if (current != null) {
            current.disconnect();
            player.setConnectedServer(null);
        }
        ServerConnection inFlight = player.connectionInFlight();
        if (inFlight != null) {
            inFlight.disconnect();
            player.endConnect(inFlight);
        }
        if (proxy.players().byUuid(player.uuid()).orElse(null) == player) {
            proxy.players().remove(player);
            // The duration separates a clean quit from a session that died on its first
            // seconds of play traffic -- the two are otherwise the same log line.
            LOG.info("{} disconnected after {}s", player.username(),
                    (System.currentTimeMillis() - player.connectedAt()) / 1000);
        }
    }

    /**
     * Reports why a backend would not take the player.
     *
     * <p>The player only ever sees a short reason, but the operator needs the underlying
     * cause: "could not reach lobby" is the same message whether the port is closed, the
     * backend hung up mid-login, or Relay hit a protocol error talking to it, and those
     * call for completely different fixes.
     */
    private void logFailure(RegisteredServer server, ConnectionResult result) {
        switch (result) {
            case ConnectionResult.Rejected rejected -> {
                String reason = plain(rejected.reason());
                LOG.info("{} was refused by {}: {}", player.username(), server.name(), reason);
                warnAboutForwarding(server, reason);
            }
            case ConnectionResult.Unreachable unreachable -> {
                Throwable cause = unreachable.cause();
                LOG.warn("{} could not reach {} ({}): {}", player.username(), server.name(),
                        server.address().getHostString() + ":" + server.address().getPort(),
                        cause == null ? "no cause recorded" : describeCause(cause));
                LOG.debug("Backend connection failure detail for {}", server.name(), cause);
            }
            case ConnectionResult.Success ignored -> {
            }
        }
    }

    /**
     * Recognises a backend refusing the login because it could not validate the
     * forwarded profile, and says what to check.
     *
     * <p>The backend's own wording &mdash; Paper's "Unable to verify player details" is
     * the usual one &mdash; describes the symptom without naming a cause, and it looks
     * identical whether the secret is wrong, absent, or forwarding is off entirely.
     * Nearly always it means the secret on the two sides does not match.
     */
    private void warnAboutForwarding(RegisteredServer server, String reason) {
        String lower = reason.toLowerCase(Locale.ROOT);
        boolean forwardingRelated = lower.contains("verify player details")
                || lower.contains("connect with velocity")
                || lower.contains("connect through")
                || lower.contains("bungeecord")
                || lower.contains("proxy");
        if (!forwardingRelated) {
            return;
        }

        ForwardingMode mode = proxy.config().forwardingMode();
        if (mode == ForwardingMode.MODERN) {
            LOG.warn("{} rejected Relay's forwarded profile. This is almost always a secret mismatch. Check that:",
                    server.name());
            LOG.warn("  1. forwarding-secret in relay.toml matches proxies.velocity.secret in the backend's "
                    + "config/paper-global.yml exactly, with no surrounding whitespace.");
            LOG.warn("  2. proxies.velocity.enabled is true on the backend.");
            LOG.warn("  3. online-mode=false in the backend's server.properties.");
            LOG.warn("  If you are migrating from Velocity, reuse that setup's existing forwarding.secret "
                    + "rather than the one Relay generated on first start.");
        } else {
            LOG.warn("{} expects proxy forwarding, but Relay is configured with forwarding-mode = \"{}\". "
                    + "Set it to \"modern\" and share a secret with the backend.",
                    server.name(), mode.configName());
        }
    }

    static String describeCauseForTest(Throwable cause) {
        return describeCause(cause);
    }

    /** Chains cause messages, since the useful one is often wrapped a level or two down. */
    private static String describeCause(Throwable cause) {
        StringBuilder message = new StringBuilder();
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (!message.isEmpty()) {
                message.append(" <- ");
            }
            message.append(current.getMessage() == null
                    ? current.getClass().getSimpleName()
                    : current.getClass().getSimpleName() + ": " + current.getMessage());
            if (current.getCause() == current) {
                break;
            }
        }
        return message.toString();
    }

    /** Flattens a component to plain text, dropping the section-sign colour codes. */
    public static String plain(Component component) {
        return LegacyComponentSerializer.legacySection().serialize(component).replaceAll("§.", "");
    }

    private static Component describe(ConnectionResult result) {
        return switch (result) {
            case ConnectionResult.Rejected rejected -> rejected.reason();
            case ConnectionResult.Unreachable unreachable -> Component.text(
                    "Could not reach " + unreachable.target().name(), NamedTextColor.RED);
            case ConnectionResult.Success ignored -> Component.empty();
        };
    }
}
