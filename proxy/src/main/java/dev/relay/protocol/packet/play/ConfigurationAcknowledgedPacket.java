package dev.relay.protocol.packet.play;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;

/** Client &rarr; server: acknowledging {@link StartConfigurationPacket}. */
public final class ConfigurationAcknowledgedPacket implements Packet {

    public static final ConfigurationAcknowledgedPacket INSTANCE = new ConfigurationAcknowledgedPacket();

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
