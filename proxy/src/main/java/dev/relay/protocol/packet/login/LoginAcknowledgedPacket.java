package dev.relay.protocol.packet.login;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;

/** The client confirming login success; moves the connection into configuration state. */
public final class LoginAcknowledgedPacket implements Packet {

    public static final LoginAcknowledgedPacket INSTANCE = new LoginAcknowledgedPacket();

    @Override
    public void decode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
    }

    @Override
    public void encode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
    }

    @Override
    public boolean handle(SessionHandler handler) {
        return handler.handle(this);
    }
}
