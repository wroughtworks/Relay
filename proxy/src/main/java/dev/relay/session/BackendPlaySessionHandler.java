package dev.relay.session;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Steady-state relay of backend traffic to the player.
 *
 * <p>Nothing is opened here. Every clientbound play packet &mdash; chunks, entities,
 * inventory, the backend's own kicks &mdash; is forwarded as an opaque frame, so this
 * direction has no dependency on packet ids at all.
 */
public final class BackendPlaySessionHandler implements SessionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(BackendPlaySessionHandler.class);

    private final RelayProxy proxy;
    private final ServerConnection server;
    private final PacketTrail fromBackend = new PacketTrail();

    /** Counted separately from received: a gap means packets were dropped, not relayed. */
    private long delivered;

    public BackendPlaySessionHandler(RelayProxy proxy, ServerConnection server) {
        this.proxy = proxy;
        this.server = server;
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
        ConnectedPlayer player = server.player();
        fromBackend.record(msg);
        if (player.connectedServer() == server && player.isActive()) {
            player.connection().relay(msg);
            delivered++;
        }
        // Traffic from a backend the player has already left is discarded: they are
        // being configured by a different server now.
    }

    @Override
    public void disconnected() {
        ConnectedPlayer player = server.player();
        if (player.connectedServer() != server) {
            // An orderly detach during a switch, already accounted for.
            return;
        }

        // Which side hung up decides where to look next: Relay closing means a proxy
        // bug, the backend closing means it rejected something Relay sent it.
        boolean byRelay = server.connection().closedLocally();
        // The player's channel state at this instant distinguishes "the backend really
        // did hang up" from "the client went first and this is the knock-on effect",
        // which otherwise produce the same log line.
        io.netty.channel.Channel playerChannel = player.connection().channel();
        LOG.warn("Backend {} connection closed {} after {}s of play, ending {}'s session "
                        + "(player channel: active={} open={} writable={})",
                server.target().name(),
                byRelay ? "by Relay" : "by the backend itself",
                (System.currentTimeMillis() - player.connectedAt()) / 1000,
                player.username(),
                playerChannel.isActive(), playerChannel.isOpen(), playerChannel.isWritable());
        if (!byRelay) {
            LOG.warn("The backend hung up without Relay asking it to. Check the backend's own log for the "
                    + "reason it dropped the connection -- a kick, an internal exception, or a packet-rate "
                    + "limit (Paper's packet-limiter in paper-global.yml kicks on sustained bursts).");
            // The last thing a peer was sent is the best evidence for why it objected.
            if (player.connection().sessionHandler() instanceof ClientPlaySessionHandler play) {
                LOG.warn("Relay had sent {} packet(s) to {}; the most recent were: {}",
                        play.trail().total(), server.target().name(), play.trail().recent());
            }
            LOG.warn("Relay had received {} packet(s) from {} and written {} of them to the player; "
                            + "the most recent were: {}",
                    fromBackend.total(), server.target().name(), delivered, fromBackend.recent());
        }

        player.setConnectedServer(null);
        server.disconnect();
        player.disconnect(Component.text("Lost connection to " + server.target().name(),
                NamedTextColor.RED));
    }

    /** The mirror of the player-side backpressure check. */
    @Override
    public void writabilityChanged() {
        ConnectedPlayer player = server.player();
        if (player.isActive()) {
            player.connection().channel().config()
                    .setAutoRead(server.connection().channel().isWritable());
        }
    }
}
