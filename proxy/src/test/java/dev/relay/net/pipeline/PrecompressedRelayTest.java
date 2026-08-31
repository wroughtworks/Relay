package dev.relay.net.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Forwarding a frame in the compressed form it arrived in.
 *
 * <p>Deflate is the most expensive thing Relay does, and almost all of it is redundant: a
 * frame relayed to a player arrived from the backend already compressed, was inflated so
 * Relay could read its packet id, and would otherwise be deflated again into different
 * bytes meaning the same thing. This is the machinery that skips the second half.
 *
 * <p>It is worth testing at this level rather than only end to end, because the failure
 * mode is not an exception. A mistake here sends a player bytes that are subtly not what
 * they should be, and the symptom appears much later as a decoder error on their client.
 */
class PrecompressedRelayTest {

    private static final int THRESHOLD = 256;

    /** Compressible, but not trivially so, and comfortably over the threshold. */
    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        Random random = new Random(42);
        for (int i = 0; i < size; i++) {
            bytes[i] = (byte) (random.nextInt(8) + (i % 16));
        }
        return bytes;
    }

    /** Puts a payload into the wire form a backend would send. */
    private static ByteBuf compress(byte[] payload) {
        EmbeddedChannel channel = new EmbeddedChannel(new CompressionEncoder(THRESHOLD, 6));
        channel.writeOutbound(Unpooled.wrappedBuffer(payload));
        ByteBuf framed = channel.readOutbound();
        channel.finishAndReleaseAll();
        return framed;
    }

    private static byte[] bytesOf(ByteBuf buf) {
        byte[] copy = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), copy);
        return copy;
    }

    @Test
    void theOriginalGoesBackOutByteForByte() {
        byte[] original = payload(4096);
        ByteBuf compressed = compress(original);
        byte[] onTheWire = bytesOf(compressed);

        CompressionDecoder decoder = new CompressionDecoder(THRESHOLD);
        EmbeddedChannel inbound = new EmbeddedChannel(decoder);
        inbound.writeInbound(compressed);
        ByteBuf inflated = inbound.readInbound();

        assertArrayEquals(original, bytesOf(inflated), "the frame did not inflate to what went in");

        ByteBuf kept = decoder.takeOriginalFor(inflated);
        assertNotNull(kept, "the decoder did not keep the frame it had just decoded");

        EmbeddedChannel outbound = new EmbeddedChannel(new CompressionEncoder(THRESHOLD, 6));
        outbound.writeOutbound(new Precompressed(kept));
        ByteBuf written = outbound.readOutbound();

        assertArrayEquals(onTheWire, bytesOf(written),
                "the frame sent on is not the frame that arrived");

        written.release();
        inflated.release();
        inbound.finishAndReleaseAll();
        outbound.finishAndReleaseAll();
    }

    /** The bytes are not merely identical: a decoder has to be able to read them. */
    @Test
    void whatComesOutStillDecodes() {
        byte[] original = payload(8192);
        ByteBuf compressed = compress(original);

        CompressionDecoder decoder = new CompressionDecoder(THRESHOLD);
        EmbeddedChannel inbound = new EmbeddedChannel(decoder);
        inbound.writeInbound(compressed);
        ByteBuf inflated = inbound.readInbound();
        ByteBuf kept = decoder.takeOriginalFor(inflated);

        EmbeddedChannel outbound = new EmbeddedChannel(new CompressionEncoder(THRESHOLD, 6));
        outbound.writeOutbound(new Precompressed(kept));
        ByteBuf written = outbound.readOutbound();

        // What a client's pipeline would do with it.
        EmbeddedChannel client = new EmbeddedChannel(new CompressionDecoder(THRESHOLD));
        client.writeInbound(written);
        ByteBuf received = client.readInbound();

        assertArrayEquals(original, bytesOf(received), "the player would not have got the packet");

        received.release();
        inflated.release();
        inbound.finishAndReleaseAll();
        outbound.finishAndReleaseAll();
        client.finishAndReleaseAll();
    }

    /** A frame under the threshold arrives with a zero marker and travels the same way. */
    @Test
    void framesTooSmallToCompressPassThroughToo() {
        byte[] original = payload(32);
        ByteBuf compressed = compress(original);

        CompressionDecoder decoder = new CompressionDecoder(THRESHOLD);
        EmbeddedChannel inbound = new EmbeddedChannel(decoder);
        inbound.writeInbound(compressed.retainedDuplicate());
        ByteBuf inflated = inbound.readInbound();
        ByteBuf kept = decoder.takeOriginalFor(inflated);
        assertNotNull(kept, "an uncompressed frame should be reusable as well");

        EmbeddedChannel client = new EmbeddedChannel(new CompressionDecoder(THRESHOLD));
        EmbeddedChannel outbound = new EmbeddedChannel(new CompressionEncoder(THRESHOLD, 6));
        outbound.writeOutbound(new Precompressed(kept));
        client.writeInbound((ByteBuf) outbound.readOutbound());

        assertArrayEquals(original, bytesOf(client.readInbound()));

        compressed.release();
        inflated.release();
        inbound.finishAndReleaseAll();
        outbound.finishAndReleaseAll();
        client.finishAndReleaseAll();
    }

    /**
     * The identity check is the safety net, so it has to actually reject.
     *
     * <p>The decoder hands back the original only for the frame it just produced. If the
     * pipeline ever stops delivering one frame at a time, this returns null and the caller
     * compresses normally -- a lost optimisation rather than a player receiving somebody
     * else's packet.
     */
    @Test
    void aFrameItDidNotDecodeIsRefused() {
        CompressionDecoder decoder = new CompressionDecoder(THRESHOLD);
        EmbeddedChannel inbound = new EmbeddedChannel(decoder);
        inbound.writeInbound(compress(payload(2048)));
        ByteBuf inflated = inbound.readInbound();

        ByteBuf stranger = Unpooled.wrappedBuffer(payload(64));
        assertNull(decoder.takeOriginalFor(stranger), "it offered up a frame it never saw");
        stranger.release();

        assertNotNull(decoder.takeOriginalFor(inflated));
        assertNull(decoder.takeOriginalFor(inflated),
                "ownership passes to the first caller; a second must not get it too");

        inflated.release();
        inbound.finishAndReleaseAll();
    }

    /** Only the newest frame is held, and the one it replaced is freed rather than leaked. */
    @Test
    void anUnclaimedFrameIsReleasedRatherThanLeaked() {
        CompressionDecoder decoder = new CompressionDecoder(THRESHOLD);
        EmbeddedChannel inbound = new EmbeddedChannel(decoder);

        inbound.writeInbound(compress(payload(1024)));
        ByteBuf first = inbound.readInbound();
        // Nobody takes it -- as happens when Relay intercepts a frame instead of relaying.
        inbound.writeInbound(compress(payload(1024)));
        ByteBuf second = inbound.readInbound();

        assertNull(decoder.takeOriginalFor(first), "the older frame should have been let go");
        ByteBuf kept = decoder.takeOriginalFor(second);
        assertNotNull(kept);
        assertTrue(kept.refCnt() > 0);

        kept.release();
        first.release();
        second.release();
        inbound.finishAndReleaseAll();
    }

    /**
     * Compressed and passed through must beat compressing again, or none of this is worth
     * the risk on the data path.
     */
    @Test
    void passingThroughIsCheaperThanCompressingAgain() {
        byte[] original = payload(8192);
        int rounds = 300;

        long deflating = time(rounds, () -> {
            EmbeddedChannel out = new EmbeddedChannel(new CompressionEncoder(THRESHOLD, 6));
            out.writeOutbound(Unpooled.wrappedBuffer(original));
            out.finishAndReleaseAll();
        });
        ByteBuf ready = compress(original);
        long passing = time(rounds, () -> {
            EmbeddedChannel out = new EmbeddedChannel(new CompressionEncoder(THRESHOLD, 6));
            out.writeOutbound(new Precompressed(ready.retainedDuplicate()));
            out.finishAndReleaseAll();
        });
        ready.release();

        assertTrue(passing * 2 < deflating,
                "passing a compressed frame through took " + passing + "ms against " + deflating
                        + "ms to deflate it again; the optimisation is not doing anything");
    }

    private static long time(int rounds, Runnable work) {
        for (int i = 0; i < 50; i++) {
            work.run();
        }
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            work.run();
        }
        return Math.max(1, (System.nanoTime() - start) / 1_000_000);
    }
}
