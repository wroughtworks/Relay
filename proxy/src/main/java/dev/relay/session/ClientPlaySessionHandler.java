package dev.relay.session;

import dev.relay.api.ClientApi;
import dev.relay.api.ClientApiSession;
import dev.relay.net.MinecraftConnection;
import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.ProtocolState;
import dev.relay.protocol.packet.PluginMessagePacket;
import dev.relay.protocol.packet.play.ChatCommandPacket;
import dev.relay.protocol.packet.play.ConfigurationAcknowledgedPacket;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Steady-state relay of player traffic to their backend.
 *
 * <p>Two packets get opened: commands, so proxy commands can be claimed before the
 * backend sees them, and the configuration acknowledgement, which is how a server switch
 * completes its handover. Everything else is forwarded without being parsed.
 */
public final class ClientPlaySessionHandler implements SessionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ClientPlaySessionHandler.class);

    private final RelayProxy proxy;
    private final ConnectedPlayer player;
    private final PacketTrail trail = new PacketTrail();

    /** Created on first contact from a modded client; stays null for vanilla players. */
    private ClientApiSession apiSession;

    public ClientPlaySessionHandler(RelayProxy proxy, ConnectedPlayer player) {
        this.proxy = proxy;
        this.player = player;
    }

    /** What this player last sent to their backend, for diagnosing an unexplained hang-up. */
    PacketTrail trail() {
        return trail;
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
        ServerConnection server = player.connectedServer();
        if (server != null && server.isActive()) {
            trail.record(msg);
            server.connection().relay(msg);
        }
        // With no backend attached the player is mid-switch; their input has nowhere
        // meaningful to go and the new backend has its own idea of their state.
    }

    /**
     * Claims Relay's own client-API channel; everything else is passed to the backend.
     *
     * <p>The API channel is never relayed. A backend has no idea what these messages
     * mean, and forwarding them would let a client reach backend plugins over a channel
     * they never opted into.
     */
    @Override
    public boolean handle(PluginMessagePacket packet) {
        if (!packet.channel().equals(ClientApi.CHANNEL)) {
            return false;
        }
        if (!proxy.config().clientApiEnabled()) {
            // Consumed regardless: with the API off the channel still belongs to Relay,
            // and a backend should not see it either way.
            return true;
        }
        if (apiSession == null) {
            apiSession = new ClientApiSession(proxy, player);
        }
        if (!apiSession.handle(packet.data())) {
            player.disconnect(Component.text("Invalid client API message", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public boolean handle(ChatCommandPacket packet) {
        if (!proxy.config().interceptCommands()) {
            return false;
        }
        // false leaves the packet to be forwarded verbatim, signatures intact.
        return proxy.commands().dispatch(player, packet.command());
    }

    /**
     * The player has agreed to leave play state, so the switch can complete: detach the
     * old backend and let the new one start configuring them.
     */
    @Override
    public boolean handle(ConfigurationAcknowledgedPacket packet) {
        ServerConnection incoming = player.connectionInFlight();
        if (incoming == null) {
            LOG.warn("{} acknowledged a configuration switch that Relay did not request",
                    player.username());
            player.disconnect(Component.text("Unexpected configuration switch", NamedTextColor.RED));
            return true;
        }

        MinecraftConnection connection = player.connection();
        connection.setState(ProtocolState.CONFIGURATION);
        connection.setSessionHandler(new ClientConfigSessionHandler(proxy, player));

        // Detach before closing, not after: closing can deliver channelInactive
        // synchronously, and BackendPlaySessionHandler reads connectedServer to tell an
        // orderly switch from a backend that died. Closing first would look like death
        // and kick the player mid-switch.
        ServerConnection outgoing = player.connectedServer();
        player.setConnectedServer(null);
        if (outgoing != null && outgoing != incoming) {
            outgoing.disconnect();
        }

        incoming.notifyClientReady();
        return true;
    }

    @Override
    public void disconnected() {
        new BackendConnector(proxy, player).handlePlayerDisconnect();
    }

    /**
     * Backpressure. If the player's socket cannot keep up, stop reading from their
     * backend rather than buffering an unbounded amount of world data for them.
     */
    @Override
    public void writabilityChanged() {
        ServerConnection server = player.connectedServer();
        if (server != null && server.isActive()) {
            server.connection().channel().config()
                    .setAutoRead(player.connection().channel().isWritable());
        }
    }
}
