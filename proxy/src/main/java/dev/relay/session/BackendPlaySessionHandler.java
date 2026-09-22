package dev.relay.session;

import dev.relay.api.backend.BackendApi;
import dev.relay.api.backend.BackendApiHandler;
import dev.relay.protocol.packet.PluginMessagePacket;
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

import java.nio.charset.StandardCharsets;

/**
 * Steady-state relay of backend traffic to the player.
 *
 * <p>Nothing is opened here. Every clientbound play packet &mdash; chunks, entities,
 * inventory, the backend's own kicks &mdash; is forwarded as an opaque frame, so this
 * direction has no dependency on packet ids at all.
 */
public final class BackendPlaySessionHandler implements SessionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(BackendPlaySessionHandler.class);

    /** The channel a peer uses to announce which plugin channels it accepts. */
    private static final String REGISTER_CHANNEL = "minecraft:register";

    private final RelayProxy proxy;
    private final ServerConnection server;
    private final PacketTrail fromBackend = new PacketTrail();
    private final BackendApiHandler api;
    /** Null when moving players off a dying backend is switched off; kicks then relay as-is. */
    private final BackendKickHandler kicks;

    /** Counted separately from received: a gap means packets were dropped, not relayed. */
    private long delivered;

    public BackendPlaySessionHandler(RelayProxy proxy, ServerConnection server) {
        this.proxy = proxy;
        this.server = server;
        this.api = proxy.config().backendApiEnabled() ? new BackendApiHandler(proxy, server) : null;
        this.kicks = proxy.config().fallbackOnBackendLoss() ? new BackendKickHandler(proxy, server) : null;
    }

    /**
     * Tells the backend which plugin channels this connection accepts.
     *
     * <p>Required, not optional. Bukkit's {@code sendPluginMessage} silently drops a
     * message whose channel the receiving client has not registered, and Relay is the
     * client as far as a backend is concerned. Without this announcement a plugin can
     * call the API all it likes and nothing ever leaves the server &mdash; which presents
     * as the proxy ignoring requests it never actually received.
     *
     * <p>The payload is the channel names separated by NUL bytes, which is what
     * {@code minecraft:register} has always carried.
     */
    @Override
    public void activated() {
        if (api == null) {
            return;
        }
        String channels = String.join("\0", BackendApi.BUNGEE_CHANNEL, BackendApi.RELAY_CHANNEL);
        server.connection().write(new PluginMessagePacket(
                REGISTER_CHANNEL, channels.getBytes(StandardCharsets.UTF_8)));
        LOG.debug("Registered {} with {} so it will send API messages",
                channels.replace('\0', ' '), server.target().name());
    }

    @Override
    public void handleUnknown(ByteBuf frame) {
        // Plugin messages the backend addresses to the proxy are claimed here rather
        // than relayed on to the player, who has no use for proxy control traffic.
        if (api != null && api.tryHandle(frame)) {
            return;
        }
        // A kick is claimed too. Relaying it would take the player off the network before
        // Relay could move them, which is exactly what a restart must not do.
        if (kicks != null && kicks.tryHandle(frame)) {
            return;
        }
        forward(frame);
    }

    @Override
    public void handleUnhandled(Packet packet) {
        forward(packet);
    }

    private void forward(Object msg) {
        ConnectedPlayer player = server.player();
        fromBackend.record(msg);
        if (player.isLeavingPlay()) {
            // The player has been asked to leave play state. Their client may already be
            // decoding as configuration, so a play packet delivered now corrupts the
            // connection. Dropped rather than queued: it is state the player is about to
            // stop having, and the new backend will send its own.
            return;
        }
        if (player.connectedServer() == server && player.isActive()) {
            // relayFrom rather than relay: this is the direction that carries world data,
            // and the backend has already compressed it once.
            player.connection().relayFrom(server.connection(), msg);
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
        // Detached first, so the rescue below starts from a player with no backend
        // rather than one still pointing at a socket that has gone.
        new BackendConnector(proxy, player).fallbackAfterLoss(server.target(),
                Component.text("Lost connection to " + server.target().name() + ".", NamedTextColor.RED));
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
