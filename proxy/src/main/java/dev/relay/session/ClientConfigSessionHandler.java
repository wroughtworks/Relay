package dev.relay.session;

import dev.relay.net.MinecraftConnection;
import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.ProtocolState;
import dev.relay.protocol.packet.config.FinishConfigurationAckPacket;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The player's side of configuration state, on first join and on every server switch.
 *
 * <p>Client packets are buffered until a backend is bound, because the client starts
 * talking the instant it acknowledges login while the backend connection is still being
 * opened.
 */
public final class ClientConfigSessionHandler implements SessionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ClientConfigSessionHandler.class);

    private final RelayProxy proxy;
    private final ConnectedPlayer player;
    private final PacketQueue pending = new PacketQueue();

    private ServerConnection target;

    public ClientConfigSessionHandler(RelayProxy proxy, ConnectedPlayer player) {
        this.proxy = proxy;
        this.player = player;
    }

    /** Attaches the backend that should receive this player's configuration traffic. */
    void bind(ServerConnection target) {
        this.target = target;
        if (!pending.isEmpty()) {
            pending.drainTo(target.connection());
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
        ServerConnection current = target;
        if (current != null && current.isActive()) {
            current.connection().relay(msg);
            return;
        }
        if (!pending.offer(msg)) {
            LOG.warn("{} sent more configuration data than Relay will buffer while connecting",
                    player.username());
            player.disconnect(Component.text("Timed out while connecting to a server", NamedTextColor.RED));
        }
    }

    /**
     * The player has finished configuring. Forwarding this ack is the moment both
     * connections cross into play state, and the order is load-bearing: the packet must
     * be encoded while the backend is still in configuration state, so the state change
     * follows the write rather than preceding it.
     */
    @Override
    public boolean handle(FinishConfigurationAckPacket packet) {
        ServerConnection current = target;
        if (current == null || !current.isActive()) {
            player.disconnect(Component.text("Lost connection to the server", NamedTextColor.RED));
            return true;
        }

        MinecraftConnection backend = current.connection();
        backend.write(packet);
        backend.setState(ProtocolState.PLAY);
        player.connection().setState(ProtocolState.PLAY);

        player.setConnectedServer(current);
        player.endConnect(current);
        // Back in play, so backend traffic may flow again.
        player.setLeavingPlay(false);
        current.markEstablished();

        player.connection().setSessionHandler(new ClientPlaySessionHandler(proxy, player));
        backend.setSessionHandler(new BackendPlaySessionHandler(proxy, current));

        LOG.info("{} is now playing on {}", player.username(), current.target().name());

        // Where they were is the only thing separating a first join from a move, since
        // connectedServer is null for the whole of a switch.
        RegisteredServer from = player.lastArrival();
        player.setLastArrival(current.target());
        if (from == null) {
            proxy.events().playerConnected(player, current.target());
        } else {
            proxy.events().playerSwitchedServer(player, from, current.target());
        }
        return true;
    }

    @Override
    public void disconnected() {
        pending.clear();
        new BackendConnector(proxy, player).handlePlayerDisconnect();
    }

    @Override
    public void deactivated() {
        pending.clear();
    }
}
