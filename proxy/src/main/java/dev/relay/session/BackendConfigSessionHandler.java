package dev.relay.session;

import dev.relay.net.MinecraftConnection;
import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.ProtocolState;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.packet.play.StartConfigurationPacket;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pipes a backend's configuration sequence &mdash; registries, tags, feature flags,
 * resource packs &mdash; through to the player.
 *
 * <p>None of it is interpreted. Relay's job here is to make sure it arrives at a client
 * that is in configuration state and ready to receive it, which is where the two entry
 * paths differ:
 * <ul>
 *   <li><b>First join</b> &mdash; the player is already configuring, having just
 *       acknowledged login. Forwarding starts immediately.</li>
 *   <li><b>Server switch</b> &mdash; the player is mid-game. Relay asks them to re-enter
 *       configuration and buffers the backend until they agree.</li>
 * </ul>
 */
public final class BackendConfigSessionHandler implements SessionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(BackendConfigSessionHandler.class);

    private final RelayProxy proxy;
    private final ServerConnection attempt;
    private final PacketQueue pending = new PacketQueue();

    private boolean clientReady;

    public BackendConfigSessionHandler(RelayProxy proxy, ServerConnection attempt) {
        this.proxy = proxy;
        this.attempt = attempt;
    }

    @Override
    public void activated() {
        ConnectedPlayer player = attempt.player();
        if (player.connection().state() == ProtocolState.CONFIGURATION) {
            onClientReady();
            return;
        }

        // Mid-game switch. Ask the client to leave play state; ClientPlaySessionHandler
        // fires the callback once it acknowledges.
        //
        // Marked as leaving before the request goes out, not after the reply comes back.
        // A client switches its own decoder to configuration state the moment it sends
        // the acknowledgement, and Relay cannot learn of that until the packet arrives --
        // so anything the old backend sends in between would reach a client decoding it
        // against the wrong state. A chunk read as configuration data is what produces
        // "Index 37 out of bounds for length 9" on the client's disconnect screen.
        player.setLeavingPlay(true);
        attempt.setClientReadyCallback(this::onClientReady);
        player.connection().write(StartConfigurationPacket.INSTANCE);
    }

    private void onClientReady() {
        clientReady = true;
        ConnectedPlayer player = attempt.player();

        if (player.connection().sessionHandler() instanceof ClientConfigSessionHandler clientConfig) {
            clientConfig.bind(attempt);
        } else {
            LOG.error("{} was expected to be in configuration state but is not; aborting switch to {}",
                    player.username(), attempt.target().name());
            attempt.markRejected(Component.text("Internal error during server switch", NamedTextColor.RED));
            attempt.connection().close();
            return;
        }

        if (!pending.isEmpty()) {
            LOG.debug("Draining queued configuration to {} for the switch to {}",
                    player.username(), attempt.target().name());
            pending.drainTo(player.connection());
        }
    }

    @Override
    public void handleUnknown(ByteBuf frame) {
        forward(frame);
    }

    @Override
    public void handleUnhandled(Packet packet) {
        forward(packet);
    }

    private void forward(Object msg) {
        MinecraftConnection client = attempt.player().connection();
        // The configuration phase of a switch is short and entirely opaque to Relay, so
        // when a client rejects it there is otherwise nothing to inspect. Logging each
        // packet id names the one it stopped on.
        if (LOG.isDebugEnabled() && msg instanceof ByteBuf frame) {
            LOG.debug("{} config -> {}: 0x{} ({}B){}", attempt.target().name(),
                    attempt.player().username(),
                    Integer.toHexString(ProtocolUtils.readVarInt(frame.duplicate())),
                    frame.readableBytes(), clientReady ? "" : " [queued]");
        }
        if (clientReady && client.isActive()) {
            // Registry data is tens of kilobytes and arrives on every join and every
            // switch, so it is worth not deflating twice. Only on the direct path: a
            // queued frame outlives the decode that produced it, and relayFrom's identity
            // check would refuse it anyway, which is the check doing its job.
            client.relayFrom(attempt.connection(), msg);
            return;
        }
        if (!pending.offer(msg)) {
            LOG.warn("Backend {} sent more configuration data than Relay will buffer while {} switches",
                    attempt.target().name(), attempt.player().username());
            attempt.markRejected(Component.text("Server sent too much data while connecting",
                    NamedTextColor.RED));
            attempt.connection().close();
        }
    }

    @Override
    public void disconnected() {
        pending.clear();
        if (!attempt.result().isDone()) {
            attempt.markUnreachable(new IllegalStateException(
                    "Backend " + attempt.target().name() + " closed the connection during configuration"));
        }
        ConnectedPlayer player = attempt.player();
        ServerConnection current = player.connectedServer();
        if (current != null && current != attempt) {
            // A switch that died before the client agreed to leave play state. The
            // player never went anywhere, so they are still on their old server and
            // whoever asked for the switch has already been told it failed.
            return;
        }

        // Otherwise the client is in configuration state with nothing configuring it,
        // which no amount of waiting fixes: either another backend picks the client up
        // where this one dropped it, or the session ends with a reason.
        new BackendConnector(proxy, player).fallbackAfterLoss(attempt.target(), Component.text(
                "Lost connection to " + attempt.target().name() + ".", NamedTextColor.RED));
    }

    @Override
    public void deactivated() {
        pending.clear();
    }
}
