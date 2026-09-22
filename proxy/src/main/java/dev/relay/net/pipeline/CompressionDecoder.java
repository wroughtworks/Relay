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

    /**
     * The frame most recently decoded, in the form it arrived in, and what it inflated to.
     *
     * <p>Kept so the other side of the proxy can forward the original rather than deflate
     * a fresh copy of the same data -- see {@link CompressionEncoder} for why that is the
     * expensive half. Only the last one is held: a frame is decoded and delivered before
     * the next is decoded, so at most one is ever in flight.
     *
     * <p>{@link #takeOriginalFor} checks the inflated buffer's <em>identity</em> before
     * handing the original back. That is what makes this safe rather than clever: if the
     * pipeline ever stops being one-frame-at-a-time, the check fails, the caller gets null
     * and compresses normally. The failure mode is a lost optimisation, not a corrupted
     * stream.
     */
    private ByteBuf lastOriginal;
    private ByteBuf lastInflated;

    public CompressionDecoder(int threshold) {
        this.threshold = threshold;
    }

    /** The threshold this connection negotiated, for deciding whether a peer can reuse it. */
    public int threshold() {
        return threshold;
    }

    /**
     * Hands over the compressed bytes {@code inflated} came from, and forgets them.
     *
     * @return the original frame, whose ownership passes to the caller, or null if this is
     *         not the frame just decoded
     */
    public ByteBuf takeOriginalFor(ByteBuf inflated) {
        if (lastInflated != inflated || lastOriginal == null) {
            return null;
        }
        ByteBuf original = lastOriginal;
        lastOriginal = null;
        lastInflated = null;
        return original;
    }

    /** Drops any original nobody claimed, so a frame Relay intercepted does not leak. */
    private void forgetOriginal() {
        if (lastOriginal != null) {
            lastOriginal.release();
            lastOriginal = null;
        }
        lastInflated = null;
    }

    private void remember(ByteBuf original, ByteBuf inflated) {
        forgetOriginal();
        lastOriginal = original;
        lastInflated = inflated;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws DataFormatException {
        // Taken before anything is read, so it is the frame exactly as it arrived --
        // size marker included, which is what the far side would have to write anyway.
        ByteBuf original = in.retainedSlice(in.readerIndex(), in.readableBytes());
        boolean kept = false;
        try {
            int claimedSize = ProtocolUtils.readVarInt(in);
            if (claimedSize == 0) {
                // Below the threshold, so it was sent uncompressed.
                ByteBuf raw = in.retainedSlice();
                in.skipBytes(in.readableBytes());
                remember(original, raw);
                kept = true;
                out.add(raw);
                return;
            }
            decodeCompressed(ctx, in, out, claimedSize, original);
            kept = true;
        } finally {
            if (!kept) {
                original.release();
            }
        }
    }

    private void decodeCompressed(ChannelHandlerContext ctx, ByteBuf in, List<Object> out,
                                  int claimedSize, ByteBuf original) throws DataFormatException {
        {
        if (claimedSize < threshold) {
            throw new ProtocolException("Compressed frame claims size " + claimedSize
                    + ", below the negotiated threshold " + threshold);
        }
        if (claimedSize > MAX_UNCOMPRESSED_SIZE) {
            throw new ProtocolException("Compressed frame claims size " + claimedSize
                    + ", exceeding the maximum " + MAX_UNCOMPRESSED_SIZE);
        }

        }
        ByteBuf decompressed = ctx.alloc().buffer(claimedSize);
        boolean success = false;
        try {
            inflate(in, decompressed, claimedSize);
            remember(original, decompressed);
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
        forgetOriginal();
        inflater.end();
    }
}
