package dev.relay.net.pipeline;

import dev.relay.protocol.Packet;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolException;
import dev.relay.protocol.ProtocolState;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import dev.relay.protocol.StateRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Serialises a {@link Packet}, prefixed with the id for the negotiated version.
 *
 * <p>Opaque {@link ByteBuf} frames being relayed do not match this encoder's type and
 * flow past it untouched, straight to the length prefixer.
 */
public final class MinecraftEncoder extends MessageToByteEncoder<Packet> {

    private final PacketDirection direction;
    private ProtocolState state = ProtocolState.HANDSHAKE;
    private ProtocolVersion version = ProtocolVersion.oldest();

    public MinecraftEncoder(PacketDirection direction) {
        super(false);
        this.direction = direction;
    }

    public void setState(ProtocolState state) {
        this.state = state;
    }

    public void setVersion(ProtocolVersion version) {
        this.version = version;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Packet msg, ByteBuf out) {
        int packetId = StateRegistry.of(state).forDirection(direction).idOf(msg.getClass(), version);
        if (packetId == -1) {
            throw new ProtocolException(msg.getClass().getSimpleName() + " has no id in "
                    + state + "/" + direction + " at " + version);
        }
        ProtocolUtils.writeVarInt(out, packetId);
        msg.encode(out, direction, version);
    }
}
