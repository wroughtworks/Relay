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
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;

/**
 * Turns a frame into a {@link Packet} when Relay has a definition for it, and passes it
 * through as an opaque {@link ByteBuf} when it does not.
 *
 * <p>That fallback is what keeps the proxy cheap: the bulk of play traffic &mdash; chunk
 * data, entity movement &mdash; is never parsed, never copied, and never allocated for.
 */
public final class MinecraftDecoder extends MessageToMessageDecoder<ByteBuf> {

    private final PacketDirection direction;
    private ProtocolState state = ProtocolState.HANDSHAKE;
    private ProtocolVersion version = ProtocolVersion.oldest();

    public MinecraftDecoder(PacketDirection direction) {
        this.direction = direction;
    }

    public void setState(ProtocolState state) {
        this.state = state;
    }

    public void setVersion(ProtocolVersion version) {
        this.version = version;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!in.isReadable()) {
            // An empty frame carries no id. Nothing legitimate produces one.
            throw new ProtocolException("Empty frame in state " + state);
        }

        int readerStart = in.readerIndex();
        int packetId = ProtocolUtils.readVarInt(in);
        Packet packet = StateRegistry.of(state).forDirection(direction).create(packetId, version);

        if (packet == null) {
            in.readerIndex(readerStart);
            out.add(in.retain());
            return;
        }

        try {
            packet.decode(in, direction, version);
        } catch (ProtocolException e) {
            throw new ProtocolException("Malformed " + packet.getClass().getSimpleName()
                    + " (id 0x" + Integer.toHexString(packetId) + ", " + state + ", " + version + ")", e);
        }

        if (in.isReadable()) {
            // Trailing bytes mean Relay's definition disagrees with the sender's. Better
            // to fail loudly here than to forward something half-understood.
            throw new ProtocolException(packet.getClass().getSimpleName() + " left " + in.readableBytes()
                    + " trailing bytes (id 0x" + Integer.toHexString(packetId) + ", " + state + ", " + version + ")");
        }
        out.add(packet);
    }
}
