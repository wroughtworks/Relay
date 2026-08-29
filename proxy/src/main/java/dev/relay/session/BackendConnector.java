package dev.relay.session;

import dev.relay.config.ForwardingMode;
import dev.relay.protocol.ProtocolState;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.ConnectionResult;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import io.netty.buffer.Unpooled;
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
 * <p>Two paths lead here, and they share the same walk down the candidate list: a player
 * joining, and a player whose backend died under them. The second is the one that decides
 * whether restarting a server is routine or ejects everyone from the network, so it is
 * worth being precise about why it can work at all.
 *
 * <p>A client that has lost its backend is not stuck. It is still in play state on a live
 * socket, and play state is exactly where a {@code /server} switch begins &mdash; ask the
 * client to re-enter configuration, then let the next backend configure it from scratch.
 * The dead server was only ever one half of the connection; nothing about its death
 * prevents another server taking over. What genuinely cannot be recovered is a client
 * that has already taken on a backend's registries and is committed to it, which is why
 * {@link ConnectedPlayer#beginRecovery()} allows one rescue chain at a time and no more.
 */
public final class BackendConnector {

    private static final Logger LOG = LoggerFactory.getLogger(BackendConnector.class);

    /** Rescues closer together than this count as one flapping episode. */
    private static final long FLAP_WINDOW_MILLIS = 30_000;

    /** How many rescues in a row Relay will attempt before letting the session end. */
    private static final int MAX_CONSECUTIVE_FALLBACKS = 3;

    private final RelayProxy proxy;
    private final ConnectedPlayer player;

    /**
     * A backend's own kick, kept whole in case the player ends up with nowhere to go.
     *
     * <p>Bytes rather than a buffer: it has to outlive the call that captured it, and a
     * retained frame would be one more thing that can leak down a path with several exits.
     */
    private byte[] verbatimKick;

    public BackendConnector(RelayProxy proxy, ConnectedPlayer player) {
        this.proxy = proxy;
        this.player = player;
    }

    /** Walks the configured try order, or the forced host for the address the player used. */
    public void connectToInitialServer() {
        List<RegisteredServer> candidates = proxy.resolveAll(
                proxy.config().initialCandidates(player.virtualHost()));
        if (!player.beginRecovery()) {
            LOG.warn("{} is already being connected somewhere; ignoring a second initial connect",
                    player.username());
            return;
        }
        proxy.metrics().routeAttempted();
        tryCandidate(candidates, 0, null);
    }

    /**
     * Moves a player whose backend died out from under them to the next server that will
     * have them, rather than ending their session with it.
     *
     * <p>Without this, restarting one backend disconnects every player on it from the
     * network entirely &mdash; they are returned to the multiplayer menu, not to the
     * lobby &mdash; which is the difference between a routine restart and an outage.
     *
     * @param lost   the backend that went away; excluded from the candidates, since it
     *               has just proved it cannot hold a session
     * @param reason shown to the player if nothing else will take them
     */
    public void fallbackAfterLoss(RegisteredServer lost, Component reason) {
        fallbackAfterLoss(lost, reason, null);
    }

    /**
     * As {@link #fallbackAfterLoss(RegisteredServer, Component)}, preserving a kick.
     *
     * @param verbatimKick the backend's own disconnect frame, sent unchanged if nothing
     *                     will take the player &mdash; its reason may be network NBT,
     *                     which Relay writes but cannot read, and a player kicked for
     *                     being banned should see the server's words rather than Relay's
     */
    public void fallbackAfterLoss(RegisteredServer lost, Component reason, byte[] verbatimKick) {
        this.verbatimKick = verbatimKick;
        if (!player.isActive()) {
            return;
        }
        if (!proxy.config().fallbackOnBackendLoss()) {
            player.disconnect(reason);
            return;
        }
        // Someone is already walking the list for this player -- an initial connect, or
        // an earlier rescue. Starting a second walk would race it for the same client.
        if (!player.beginRecovery()) {
            LOG.debug("{} lost {} while already being connected elsewhere; leaving it to that attempt",
                    player.username(), lost.name());
            return;
        }

        // Resolved before filtering, so a group is excluded one member at a time: losing
        // survival-01 leaves survival-02 as the first thing tried, which is the whole
        // point of running more than one.
        List<RegisteredServer> candidates = proxy.resolveAll(
                        proxy.config().initialCandidates(player.virtualHost())).stream()
                .filter(candidate -> candidate != lost)
                .toList();
        if (candidates.isEmpty()) {
            LOG.info("Nowhere to move {} after losing {}: it is the only backend they could be sent to",
                    player.username(), lost.name());
            giveUp(reason);
            return;
        }

        int consecutive = player.recordFallback(FLAP_WINDOW_MILLIS);
        if (consecutive > MAX_CONSECUTIVE_FALLBACKS) {
            LOG.warn("{} has been moved {} times in under {}s; ending the session instead of "
                            + "bouncing them between servers that will not hold a connection",
                    player.username(), consecutive - 1, FLAP_WINDOW_MILLIS / 1000);
            giveUp(reason);
            return;
        }

        proxy.metrics().routeAttempted();
        proxy.metrics().failover();
        LOG.info("Moving {} off {} after it dropped them; trying {}",
                player.username(), lost.name(), names(candidates));
        // Said now, while the player is still in play state: the switch takes them out of
        // it, and chat sent in configuration state has nowhere to render.
        player.sendMessage(Component.text().append(reason)
                .append(Component.text(" Moving you to another server...", NamedTextColor.YELLOW))
                .build());
        tryCandidate(candidates, 0, reason);
    }

    private void giveUp(Component reason) {
        proxy.metrics().routeFailed();
        player.endRecovery();
        if (verbatimKick != null && player.connection().state() == ProtocolState.PLAY) {
            LOG.info("Nothing else would take {}; passing on the kick they were given",
                    player.username());
            player.connection().closeWithFrame(Unpooled.wrappedBuffer(verbatimKick));
            return;
        }
        player.disconnect(reason);
    }

    private static List<String> names(List<RegisteredServer> candidates) {
        return candidates.stream().map(RegisteredServer::name).toList();
    }

    private void tryCandidate(List<RegisteredServer> candidates, int index, Component lastReason) {
        if (!player.isActive()) {
            player.endRecovery();
            return;
        }
        if (index >= candidates.size()) {
            giveUp(lastReason != null
                    ? lastReason
                    : Component.text("No available server to connect you to", NamedTextColor.RED));
            return;
        }

        RegisteredServer server = candidates.get(index);
        connect(server).whenComplete((result, error) -> player.connection().channel().eventLoop().execute(() -> {
            if (error != null) {
                LOG.error("Error connecting {} to {}", player.username(), server.name(), error);
                tryCandidate(candidates, index + 1, Component.text("Could not connect you to a server",
                        NamedTextColor.RED));
                return;
            }
            if (result.successful()) {
                player.endRecovery();
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
            proxy.events().playerDisconnected(player, player.lastArrival());
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
