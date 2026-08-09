package dev.relay.net.pipeline;

import dev.relay.protocol.ProtocolException;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Splits the TCP stream into packets on their VarInt length prefix.
 *
 * <p>The length is read without consuming it until the whole frame has arrived, so a
 * partially received packet simply waits for more bytes.
 */
public final class VarintFrameDecoder extends ByteToMessageDecoder {

    /** Mojang's own ceiling. Anything larger is a malformed or hostile frame. */
    private static final int MAX_PACKET_SIZE = 2 * 1024 * 1024;

    /** First byte of the pre-1.7 server list ping, which is not VarInt framed. */
    private static final int LEGACY_PING_MAGIC = 0xFE;

    private final boolean detectLegacyPing;
    private boolean firstBytesSeen;

    /**
     * @param detectLegacyPing whether the very first byte may be a pre-1.7 server list
     *                         ping. True only for player connections: a backend never
     *                         sends one, and a client cannot send one after its first
     *                         byte
     */
    public VarintFrameDecoder(boolean detectLegacyPing) {
        this.detectLegacyPing = detectLegacyPing;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!in.isReadable()) {
            return;
        }

        // Only ever the first byte of a player connection.
        //
        // This check used to run on every frame of every connection, which is a subtle
        // way to destroy a healthy session: 0xFE is also the first byte of the length
        // prefix of any frame whose length is 126 more than a multiple of 128 --- a
        // 254-byte packet encodes as 0xFE 0x01, exactly the legacy ping signature. Around
        // one frame in 128 therefore looked like a legacy ping, and a busy connection hits
        // one within seconds. The connection was then closed with no exception and no
        // disconnect packet, so both ends blamed the other.
        if (!firstBytesSeen) {
            firstBytesSeen = true;
            if (detectLegacyPing && in.getUnsignedByte(in.readerIndex()) == LEGACY_PING_MAGIC) {
                // A client far below Relay's floor. Nothing useful to say to it in a
                // format it understands, so drop it rather than fail to parse a VarInt.
                in.clear();
                ctx.close();
                return;
            }
        }

        in.markReaderIndex();
        int length = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (!in.isReadable()) {
                in.resetReaderIndex();
                return;
            }
            byte b = in.readByte();
            length |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                if (length < 0) {
                    throw new ProtocolException("Frame has negative length " + length);
                }
                if (length > MAX_PACKET_SIZE) {
                    throw new ProtocolException("Frame length " + length + " exceeds maximum " + MAX_PACKET_SIZE);
                }
                if (in.readableBytes() < length) {
                    in.resetReaderIndex();
                    return;
                }
                out.add(in.readRetainedSlice(length));
                return;
            }
        }
        throw new ProtocolException("Frame length VarInt too wide (>5 bytes)");
    }
}
