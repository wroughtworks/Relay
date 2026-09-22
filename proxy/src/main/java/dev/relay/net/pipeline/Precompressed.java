package dev.relay.net.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.DefaultByteBufHolder;

/**
 * A frame that is already in Minecraft's compressed form and must not be compressed again.
 *
 * <p>Carries the bytes exactly as {@link CompressionEncoder} would have produced them: a
 * VarInt uncompressed size, then either a deflate stream or, behind a zero, the raw
 * payload. They came off a backend connection in that shape, so the cheapest thing Relay
 * can do with them is nothing.
 *
 * <p>A distinct type rather than a flag, because the decision has to survive the trip down
 * an outbound pipeline that otherwise sees only {@code ByteBuf}s and would compress one.
 * Extends {@link DefaultByteBufHolder} so Netty's reference counting handles it exactly as
 * it handles a buffer &mdash; {@code MessageToByteEncoder} releases whatever it encodes,
 * and this is released with it.
 */
public final class Precompressed extends DefaultByteBufHolder {

    public Precompressed(ByteBuf content) {
        super(content);
    }
}
