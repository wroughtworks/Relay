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

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int length = msg.readableBytes();
        out.ensureWritable(ProtocolUtils.varIntBytes(length) + length);
        ProtocolUtils.writeVarInt(out, length);
        out.writeBytes(msg);
    }
}
