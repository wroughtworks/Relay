package dev.relay.net.pipeline;

import dev.relay.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/** Prepends the VarInt length prefix. The last thing to touch a frame before encryption. */
@ChannelHandler.Sharable
public final class VarintLengthEncoder extends MessageToByteEncoder<ByteBuf> {

    public static final VarintLengthEncoder INSTANCE = new VarintLengthEncoder();

    private VarintLengthEncoder() {
        super(false);
    }

    /**
     * Asks for a buffer the frame will actually fit in.
     *
     * <p>{@link MessageToByteEncoder} otherwise hands out its default 256 bytes, and a
     * 256-byte packet plus its length prefix does not fit in 256 bytes -- so the very
     * common case reallocated and copied on every single frame. A profile of the
     * forwarding path had {@code PoolArena.reallocate} in it for exactly this reason.
     */
    @Override
    protected ByteBuf allocateBuffer(ChannelHandlerContext ctx, ByteBuf msg, boolean preferDirect) {
        int size = ProtocolUtils.varIntBytes(msg.readableBytes()) + msg.readableBytes();
        return preferDirect ? ctx.alloc().ioBuffer(size, size) : ctx.alloc().heapBuffer(size, size);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int length = msg.readableBytes();
        ProtocolUtils.writeVarInt(out, length);
        out.writeBytes(msg);
    }
}
