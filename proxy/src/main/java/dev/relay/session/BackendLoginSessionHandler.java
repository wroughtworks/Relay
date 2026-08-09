package dev.relay.session;

import dev.relay.config.ForwardingMode;
import dev.relay.forwarding.LegacyForwarding;
import dev.relay.forwarding.ModernForwarding;
import dev.relay.net.MinecraftConnection;
import dev.relay.net.ProxyProtocol;
import dev.relay.net.SessionHandler;
import dev.relay.protocol.ProtocolState;
import dev.relay.protocol.packet.HandshakePacket;
import dev.relay.protocol.packet.login.EncryptionRequestPacket;
import dev.relay.protocol.packet.login.LoginAcknowledgedPacket;
import dev.relay.protocol.packet.login.LoginDisconnectPacket;
import dev.relay.protocol.packet.login.LoginPluginRequestPacket;
import dev.relay.protocol.packet.login.LoginPluginResponsePacket;
import dev.relay.protocol.packet.login.LoginStartPacket;
import dev.relay.protocol.packet.login.LoginSuccessPacket;
import dev.relay.protocol.packet.login.SetCompressionPacket;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import dev.relay.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.handler.timeout.TimeoutException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Logs Relay into a backend on a player's behalf.
 *
 * <p>The backend never sees the player's socket. Relay presents the player's already
 * authenticated profile, and the backend trusts it to whatever degree the configured
 * forwarding mode allows.
 */
public final class BackendLoginSessionHandler implements SessionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(BackendLoginSessionHandler.class);

    /** Handshake "next state" value for a login, as opposed to a status ping. */
    private static final int NEXT_STATE_LOGIN = 2;

    private final RelayProxy proxy;
    private final ServerConnection attempt;

    public BackendLoginSessionHandler(RelayProxy proxy, ServerConnection attempt) {
        this.proxy = proxy;
        this.attempt = attempt;
    }

    /** Set once the backend has said something, to tell "refused" from "never answered". */
    private boolean heardFromBackend;

    /** Set when Relay gave up waiting, rather than the backend hanging up. */
    private boolean timedOut;

    @Override
    public void activated() {
        ConnectedPlayer player = attempt.player();
        MinecraftConnection connection = attempt.connection();
        InetSocketAddress address = attempt.target().address();

        String hostname = switch (proxy.config().forwardingMode()) {
            case LEGACY -> LegacyForwarding.createHostname(
                    address.getHostString(), player.remoteIp(), player.profile());
            case MODERN, NONE -> address.getHostString();
        };

        // The PROXY header, if enabled, must be the very first bytes on the connection --
        // before the handshake and outside Minecraft's framing. A backend expecting one
        // reads nothing at all until it arrives.
        if (proxy.config().proxyProtocolSend() && player.remoteAddress() instanceof InetSocketAddress source
                && connection.channel().remoteAddress() instanceof InetSocketAddress destination) {
            connection.delayedWrite(ProxyProtocol.header(source, destination));
        }

        // The handshake has to be encoded in handshake state and the login packet in
        // login state, so the transition sits between the two writes.
        connection.delayedWrite(new HandshakePacket(
                player.version().id(), hostname, address.getPort(), NEXT_STATE_LOGIN));
        connection.setState(ProtocolState.LOGIN);
        connection.write(new LoginStartPacket(player.username(), player.uuid()));

        LOG.debug("Sent handshake and login start to {} for {} (protocol {})",
                attempt.target().name(), player.username(), player.version().id());
    }

    /**
     * A login-state packet Relay has no definition for.
     *
     * <p>Worth a warning rather than a silent drop: the login exchange is a strict
     * request/response sequence, so anything unrecognised here means the login is about
     * to stall with no other evidence of why. 1.20.5 cookie requests land here.
     */
    @Override
    public void handleUnknown(ByteBuf frame) {
        heardFromBackend = true;
        int packetId = ProtocolUtils.readVarInt(frame.duplicate());
        LOG.warn("Backend {} sent an unsupported login packet (id 0x{}); Relay cannot answer it and "
                        + "the login will not complete",
                attempt.target().name(), Integer.toHexString(packetId));
    }

    @Override
    public boolean handle(SetCompressionPacket packet) {
        heardFromBackend = true;
        // The backend dictates compression on this link, independently of Relay's own
        // compression-threshold, which governs only the player connection. Logged
        // because that distinction is easy to miss: turning compression off in
        // relay.toml does not turn it off here.
        LOG.debug("Backend {} negotiated a compression threshold of {} bytes",
                attempt.target().name(), packet.threshold());
        attempt.connection().enableCompression(packet.threshold(), proxy.config().compressionLevel());
        return true;
    }

    @Override
    public boolean handle(EncryptionRequestPacket packet) {
        heardFromBackend = true;
        // A backend in online mode would try to authenticate Relay against Mojang as if
        // it were a player. Say so plainly — this is the single most common setup error.
        LOG.error("Backend {} is running in online mode. Set online-mode=false in its "
                        + "server.properties and configure {} forwarding.",
                attempt.target(), proxy.config().forwardingMode().configName());
        fail(Component.text("Backend server is misconfigured (online-mode must be false)",
                NamedTextColor.RED));
        return true;
    }

    @Override
    public boolean handle(LoginPluginRequestPacket packet) {
        heardFromBackend = true;
        MinecraftConnection connection = attempt.connection();

        if (proxy.config().forwardingMode() == ForwardingMode.MODERN
                && packet.channel().equals(ModernForwarding.CHANNEL)) {
            byte[] response = ModernForwarding.createResponse(
                    proxy.config().forwardingSecret(),
                    attempt.player().remoteIp(),
                    attempt.player().profile(),
                    ModernForwarding.requestedVersion(packet.data()));
            connection.write(new LoginPluginResponsePacket(packet.messageId(), true, response));
            return true;
        }

        // Anything else is a plugin channel Relay does not implement. A negative response
        // is the protocol's way of saying so; silence would hang the login.
        connection.write(new LoginPluginResponsePacket(packet.messageId(), false, new byte[0]));
        return true;
    }

    @Override
    public boolean handle(LoginSuccessPacket packet) {
        heardFromBackend = true;
        MinecraftConnection connection = attempt.connection();
        connection.write(LoginAcknowledgedPacket.INSTANCE);
        connection.setState(ProtocolState.CONFIGURATION);
        connection.setSessionHandler(new BackendConfigSessionHandler(proxy, attempt));
        return true;
    }

    @Override
    public boolean handle(LoginDisconnectPacket packet) {
        heardFromBackend = true;
        fail(packet.reason());
        return true;
    }

    @Override
    public void disconnected() {
        if (attempt.result().isDone()) {
            return;
        }
        // These three point at completely different faults, so they get different text.
        String name = attempt.target().name();
        String message;
        if (heardFromBackend) {
            message = "Backend " + name + " hung up part-way through login. Check its version against the "
                    + "player's, and that online-mode=false with matching forwarding.";
        } else if (timedOut) {
            // The socket opened and Relay's handshake went out, but nothing came back.
            // Something is consuming the bytes without answering -- most often a
            // protocol wrapper the proxy is not speaking.
            message = "Backend " + name + " accepted the connection and Relay's handshake, then sent nothing "
                    + "back before the read timeout. The usual cause is the backend expecting a wrapper "
                    + "protocol Relay does not send: check proxies.proxy-protocol in its paper-global.yml is "
                    + "false, since Relay does not emit HAProxy PROXY headers. Otherwise check it has finished "
                    + "starting.";
        } else {
            message = "Backend " + name + " accepted the TCP connection then closed it without answering the "
                    + "handshake. Check that it is a Minecraft server on that port, that it has finished "
                    + "starting, and that its firewall/whitelist allows the proxy.";
        }
        attempt.markUnreachable(new IllegalStateException(message));
    }

    @Override
    public void exception(Throwable cause) {
        if (cause instanceof TimeoutException) {
            // Record it and let disconnected() report; the close follows immediately and
            // it has the context to say what the silence probably means.
            timedOut = true;
            return;
        }
        if (!attempt.result().isDone()) {
            attempt.markUnreachable(cause);
        }
    }

    private void fail(Component reason) {
        attempt.markRejected(reason);
        attempt.connection().close();
    }
}
