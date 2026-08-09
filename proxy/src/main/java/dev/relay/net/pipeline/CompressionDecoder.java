package dev.relay.net.pipeline;

import dev.relay.protocol.ProtocolException;
import dev.relay.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reverses Minecraft's compressed framing: a VarInt uncompressed size, then either raw
 * bytes (size 0) or a zlib stream.
 *
 * <p>The declared size is treated as an upper bound and enforced during inflation, so a
 * decompression bomb is refused rather than allocated.
 */
public final class CompressionDecoder extends MessageToMessageDecoder<ByteBuf> {

    private static final int MAX_UNCOMPRESSED_SIZE = 8 * 1024 * 1024;

    private final Inflater inflater = new Inflater();
    private final int threshold;

    public CompressionDecoder(int threshold) {
        this.threshold = threshold;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws DataFormatException {
        int claimedSize = ProtocolUtils.readVarInt(in);
        if (claimedSize == 0) {
            // Below the threshold, so it was sent uncompressed.
            out.add(in.retainedSlice());
            in.skipBytes(in.readableBytes());
            return;
        }
        if (claimedSize < threshold) {
            throw new ProtocolException("Compressed frame claims size " + claimedSize
                    + ", below the negotiated threshold " + threshold);
        }
        if (claimedSize > MAX_UNCOMPRESSED_SIZE) {
            throw new ProtocolException("Compressed frame claims size " + claimedSize
                    + ", exceeding the maximum " + MAX_UNCOMPRESSED_SIZE);
        }

        ByteBuf decompressed = ctx.alloc().buffer(claimedSize);
        boolean success = false;
        try {
            inflate(in, decompressed, claimedSize);
            out.add(decompressed);
            success = true;
        } finally {
            if (!success) {
                decompressed.release();
            }
        }
    }

    private void inflate(ByteBuf in, ByteBuf out, int expectedSize) throws DataFormatException {
        byte[] input = new byte[in.readableBytes()];
        in.readBytes(input);
        inflater.setInput(input);

        byte[] chunk = new byte[8192];
        int total = 0;
        while (!inflater.finished()) {
            int produced = inflater.inflate(chunk);
            if (produced == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) {
                    break;
                }
                continue;
            }
            total += produced;
            if (total > expectedSize) {
                throw new ProtocolException("Frame inflated past its declared size of " + expectedSize + " bytes");
            }
            out.writeBytes(chunk, 0, produced);
        }
        if (total != expectedSize) {
            throw new ProtocolException("Frame inflated to " + total + " bytes, but declared " + expectedSize);
        }
        inflater.reset();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        inflater.end();
    }
}
