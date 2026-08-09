package dev.relay.net.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import java.util.zip.Deflater;

import dev.relay.protocol.ProtocolException;
import dev.relay.protocol.ProtocolUtils;

/**
 * Applies Minecraft's compressed framing.
 *
 * <p>Frames below the negotiated threshold are sent verbatim behind a zero size marker;
 * only larger ones pay for deflation.
 */
public final class CompressionEncoder extends MessageToByteEncoder<ByteBuf> {

    private final Deflater deflater;
    private final int threshold;

    public CompressionEncoder(int threshold, int level) {
        super(false);
        this.threshold = threshold;
        this.deflater = new Deflater(level);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int uncompressedSize = msg.readableBytes();
        if (uncompressedSize < threshold) {
            ProtocolUtils.writeVarInt(out, 0);
            out.writeBytes(msg);
            return;
        }

        ProtocolUtils.writeVarInt(out, uncompressedSize);

        byte[] input = new byte[uncompressedSize];
        msg.readBytes(input);
        deflater.setInput(input);
        deflater.finish();

        byte[] chunk = new byte[8192];
        while (!deflater.finished()) {
            int produced = deflater.deflate(chunk);
            if (produced == 0) {
                // After finish() the deflater should always produce until finished. If it
                // somehow does not, stopping here would emit a valid length prefix over a
                // truncated zlib stream -- corruption the peer discovers and Relay never
                // sees. Fail loudly instead; the connection dies either way, but with a
                // cause attached.
                deflater.reset();
                throw new ProtocolException("Deflater stalled after " + out.readableBytes()
                        + " bytes while compressing a " + uncompressedSize + " byte packet");
            }
            out.writeBytes(chunk, 0, produced);
        }
        deflater.reset();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        deflater.end();
    }
}
