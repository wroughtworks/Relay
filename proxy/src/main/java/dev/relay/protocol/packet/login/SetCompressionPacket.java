package dev.relay.protocol.packet.login;

import dev.relay.net.SessionHandler;
import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;

/**
 * Switches the connection to compressed framing.
 *
 * <p>The threshold takes effect on the packet <em>after</em> this one, so the pipeline
 * must be reconfigured only once this packet has been fully written or read.
 */
public final class SetCompressionPacket implements Packet {

    private int threshold;

    public SetCompressionPacket() {
    }

    public SetCompressionPacket(int threshold) {
        this.threshold = threshold;
    }

    public int threshold() {
        return threshold;
    }

    @Override
    public void decode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        this.threshold = ProtocolUtils.readVarInt(buf);
    }

    @Override
    public void encode(ByteBuf buf, PacketDirection direction, ProtocolVersion version) {
        ProtocolUtils.writeVarInt(buf, threshold);
    }

    @Override
    public boolean handle(SessionHandler handler) {
        return handler.handle(this);
    }
}
