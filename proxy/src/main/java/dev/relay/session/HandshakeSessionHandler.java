package dev.relay.session;

import dev.relay.net.MinecraftConnection;
import dev.relay.net.SessionHandler;
import dev.relay.protocol.ProtocolState;
import dev.relay.protocol.ProtocolVersion;
import dev.relay.protocol.packet.HandshakePacket;
import dev.relay.protocol.packet.login.LoginDisconnectPacket;
import dev.relay.proxy.RelayProxy;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Handles the single packet that decides whether this is a ping or a login. */
public final class HandshakeSessionHandler implements SessionHandler {

    private final RelayProxy proxy;
    private final MinecraftConnection connection;

    public HandshakeSessionHandler(RelayProxy proxy, MinecraftConnection connection) {
        this.proxy = proxy;
        this.connection = connection;
    }

    @Override
    public boolean handle(HandshakePacket packet) {
        ProtocolState next = ProtocolState.fromHandshakeId(packet.nextState());
        if (next == null) {
            // Not a state any client should ask for. Nothing to say back.
            connection.close();
            return true;
        }

        ProtocolVersion version = ProtocolVersion.byId(packet.protocolVersion());
        connection.setState(next);

        if (next == ProtocolState.STATUS) {
            // Answer pings from any version. Status packet ids do not vary, so an
            // unsupported client can still be told what Relay speaks.
            connection.setVersion(version != null ? version : ProtocolVersion.newest());
            connection.setSessionHandler(
                    new StatusSessionHandler(proxy, connection, packet.protocolVersion()));
            return true;
        }

        if (version == null) {
            // Login-state disconnect has a stable id and a stable JSON payload at every
            // version, so this reaches even a client Relay otherwise cannot speak to.
            connection.setVersion(ProtocolVersion.newest());
            connection.closeWith(LoginDisconnectPacket.of(unsupportedVersion(packet.protocolVersion())));
            return true;
        }

        connection.setVersion(version);
        connection.setSessionHandler(new LoginSessionHandler(proxy, connection, packet));
        return true;
    }

    private static Component unsupportedVersion(int protocol) {
        return Component.text()
                .append(Component.text("Unsupported client version", NamedTextColor.RED))
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("This server accepts Minecraft ", NamedTextColor.GRAY))
                .append(Component.text(ProtocolVersion.supportedRange(), NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("Your client reported protocol " + protocol, NamedTextColor.DARK_GRAY))
                .build();
    }
}
