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
 *
 * <h2>The most expensive thing the proxy does, and how most of it is skipped</h2>
 * Measured against real Paper backends with 40 players: uncompressed forwarding costs
 * about 38us of proxy CPU per frame, compressed forwarding 177us at zlib's default level
 * and 98us at level 3. Deflate is roughly ten times the cost of inflate, so nearly all of
 * that is here, on the way out to the player.
 *
 * <p>Most of it is also redundant. A frame relayed to a player arrived from the backend
 * <em>already compressed</em>, was inflated only so Relay could read its packet id, and
 * would otherwise be deflated again into different bytes meaning the same thing. So the
 * decoder keeps the frame it decoded, and {@link dev.relay.net.MinecraftConnection#relayFrom}
 * hands the original straight back out as a {@link Precompressed} when the two connections
 * agree on compression closely enough. Same measurement, same backends: 98us a frame
 * becomes 47us.
 *
 * <p>The precondition is not equality but direction: this connection's threshold must be
 * at or below the source's, because a client rejects a frame whose declared uncompressed
 * size falls under the threshold it negotiated. A connection with no compression at all
 * has no encoder here, so it never takes the shortcut and always receives plain frames.
  */
public final class CompressionEncoder extends MessageToByteEncoder<Object> {

    private final Deflater deflater;
    private final int threshold;

    /** The negotiated threshold, so a peer can tell whether its frames are reusable here. */
    public int threshold() {
        return threshold;
    }

    public CompressionEncoder(int threshold, int level) {
        super(false);
        this.threshold = threshold;
        this.deflater = new Deflater(level);
    }

    /**
     * Only frames, in either form. Nothing else reaches here: the Minecraft encoder sits
     * closer to the handler and has already turned any packet into bytes.
     */
    @Override
    public boolean acceptOutboundMessage(Object msg) {
        return msg instanceof ByteBuf || msg instanceof Precompressed;
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Object message, ByteBuf out) {
        if (message instanceof Precompressed precompressed) {
            // Already in exactly this format, from a backend that compressed it at a
            // threshold this connection can accept. Copying it out is the whole point:
            // deflating it again would produce different bytes that mean the same thing,
            // for about a hundred microseconds a frame.
            out.writeBytes(precompressed.content());
            return;
        }
        ByteBuf msg = (ByteBuf) message;
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
