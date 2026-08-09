package dev.relay.protocol.packet.play;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;

/**
 * Server &rarr; client: leave play and re-enter configuration.
 *
 * <p>This is the packet that makes clean server switching possible on 1.20.2+. Relay
 * sends it to the player, waits for {@link ConfigurationAcknowledgedPacket}, then walks
 * the player through the new backend's configuration sequence. Before this packet
 * existed, proxies had to fake a dimension change and hope the client resynchronised.
 */
public final class StartConfigurationPacket implements Packet {

    public static final StartConfigurationPacket INSTANCE = new StartConfigurationPacket();

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
