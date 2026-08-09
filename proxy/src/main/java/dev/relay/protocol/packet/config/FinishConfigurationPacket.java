package dev.relay.protocol.packet.config;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;

/** Server &rarr; client: configuration is done, move to play. */
public final class FinishConfigurationPacket implements Packet {

    public static final FinishConfigurationPacket INSTANCE = new FinishConfigurationPacket();

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
