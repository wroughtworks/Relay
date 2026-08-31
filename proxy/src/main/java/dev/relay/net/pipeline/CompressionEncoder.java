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
 * <h2>This is the most expensive thing the proxy does</h2>
 * Measured against real Paper backends with 40 players: uncompressed forwarding costs
 * about 38us of proxy CPU per frame, and compressed forwarding costs 101us at level 3 or
 * 177us at zlib's default of 6. Deflate is roughly ten times the cost of inflate, so
 * nearly all of that is here, in this class, on the way out to the player.
 *
 * <h2>The optimisation not taken</h2>
 * Most of that work is redundant. A frame being relayed to a player arrived from the
 * backend <em>already compressed</em>, was inflated so Relay could read its packet id, and
 * is then deflated again into something very close to the bytes it started as. If the
 * client's threshold equals the backend's, the original compressed frame is exactly what
 * the client needs and could be written straight out, skipping this class entirely.
 *
 * <p>The obstacle is that the packet id lives inside the compressed payload, so Relay
 * cannot know whether a frame is one it wants to intercept -- a kick, a plugin message --
 * without inflating it. That is survivable: inflate is the cheap direction, so keeping the
 * original compressed slice beside the inflated one and writing the original whenever the
 * frame turns out to be a plain relay would still avoid the expensive half.
 *
 * <p>It is not done because it is a change to the data path with two buffers per frame to
 * account for, and getting reference counting wrong there corrupts live connections rather
 * than failing a test. The threshold equality is also a real precondition, not a detail:
 * a vanilla client rejects a frame whose declared size is below the threshold it
 * negotiated, so pass-through has to be switched off whenever the two sides disagree.
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
