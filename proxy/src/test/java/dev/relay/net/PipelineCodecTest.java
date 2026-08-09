package dev.relay.net;

import dev.relay.net.pipeline.CipherDecoder;
import dev.relay.net.pipeline.CipherEncoder;
import dev.relay.net.pipeline.CompressionDecoder;
import dev.relay.net.pipeline.CompressionEncoder;
import dev.relay.net.pipeline.MinecraftDecoder;
import dev.relay.net.pipeline.MinecraftEncoder;
import dev.relay.net.pipeline.VarintFrameDecoder;
import dev.relay.net.pipeline.VarintLengthEncoder;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolState;
import dev.relay.protocol.ProtocolVersion;
import dev.relay.protocol.packet.HandshakePacket;
import dev.relay.protocol.packet.login.LoginStartPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the codec stack end to end.
 *
 * <p>Each test wires an encoder chain into a decoder chain and pushes real packets
 * through, because the failure mode these guard against is handler <em>ordering</em> —
 * something that compiles fine, looks right in isolation, and produces a desynchronised
 * stream on a live connection.
 */
class PipelineCodecTest {

    private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;

    @Test
    void frameDecoderSplitsConcatenatedFrames() {
        EmbeddedChannel channel = new EmbeddedChannel(new VarintFrameDecoder(false));
        ByteBuf stream = Unpooled.buffer();
        stream.writeByte(3).writeBytes(new byte[]{1, 2, 3});
        stream.writeByte(2).writeBytes(new byte[]{4, 5});

        assertTrue(channel.writeInbound(stream));

        ByteBuf first = channel.readInbound();
        ByteBuf second = channel.readInbound();
        assertEquals(3, first.readableBytes());
        assertEquals(2, second.readableBytes());
        assertNull(channel.readInbound());
        first.release();
        second.release();
        channel.finishAndReleaseAll();
    }

    /** A frame split across TCP reads must be held back until it is complete. */
    @Test
    void frameDecoderWaitsForPartialFrames() {
        EmbeddedChannel channel = new EmbeddedChannel(new VarintFrameDecoder(false));

        channel.writeInbound(Unpooled.buffer().writeByte(4).writeBytes(new byte[]{1, 2}));
        assertNull(channel.readInbound(), "an incomplete frame must not be emitted");

        channel.writeInbound(Unpooled.buffer().writeBytes(new byte[]{3, 4}));
        ByteBuf frame = channel.readInbound();
        assertNotNull(frame);
        assertEquals(4, frame.readableBytes());
        frame.release();
        channel.finishAndReleaseAll();
    }

    /**
     * A 254-byte frame encodes its length as {@code 0xFE 0x01} --- byte for byte the
     * pre-1.7 legacy ping signature.
     *
     * <p>This is a regression guard for a real fault: the legacy-ping check ran on every
     * frame of every connection, so roughly one frame in 128 (any length that is 126 more
     * than a multiple of 128) was mistaken for a ping and the connection silently closed.
     * A busy connection hit one within seconds, with no exception and no disconnect
     * packet, leaving each end convinced the other had hung up.
     */
    @Test
    void framesWhoseLengthLooksLikeALegacyPingAreDecodedNormally() {
        // 254, 382, 510 ... every length whose low seven bits are 0x7E.
        for (int size : new int[]{254, 382, 510, 1022, 16382}) {
            EmbeddedChannel channel = new EmbeddedChannel(new VarintFrameDecoder(true));

            // A handshake first, as any real connection begins, so the legacy-ping
            // window has passed. That window is the one place the ambiguity is
            // irreducible: a 254-byte opening frame is byte-identical to a legacy ping,
            // and no vanilla client sends a handshake that large.
            ByteBuf handshake = Unpooled.buffer();
            ProtocolUtils.writeVarInt(handshake, 4);
            handshake.writeBytes(new byte[4]);
            channel.writeInbound(handshake);
            ByteBuf first = channel.readInbound();
            assertNotNull(first);
            first.release();

            ByteBuf framed = Unpooled.buffer();
            ProtocolUtils.writeVarInt(framed, size);
            assertEquals(0xFE, framed.getUnsignedByte(0),
                    "size " + size + " should encode with a 0xFE first byte");
            framed.writeBytes(new byte[size]);

            assertTrue(channel.writeInbound(framed), "size " + size + " produced no frame");
            ByteBuf decoded = channel.readInbound();
            assertNotNull(decoded, "size " + size + " was swallowed");
            assertEquals(size, decoded.readableBytes());
            assertTrue(channel.isOpen(), "size " + size + " was mistaken for a legacy ping and closed");

            decoded.release();
            channel.finishAndReleaseAll();
        }
    }

    /** The legacy ping itself must still be recognised, but only as the opening byte. */
    @Test
    void aLegacyPingOnTheFirstByteStillClosesThePlayerConnection() {
        EmbeddedChannel channel = new EmbeddedChannel(new VarintFrameDecoder(true));
        channel.writeInbound(Unpooled.buffer().writeByte(0xFE).writeByte(0x01));

        assertFalse(channel.isOpen(), "a real legacy ping should still be dropped");
        channel.finishAndReleaseAll();
    }

    /** A backend never sends a legacy ping, so its frames are never tested for one. */
    @Test
    void backendConnectionsNeverTreatDataAsALegacyPing() {
        EmbeddedChannel channel = new EmbeddedChannel(new VarintFrameDecoder(false));

        ByteBuf framed = Unpooled.buffer();
        ProtocolUtils.writeVarInt(framed, 254);
        framed.writeBytes(new byte[254]);
        channel.writeInbound(framed);

        ByteBuf decoded = channel.readInbound();
        assertNotNull(decoded, "the frame was swallowed");
        assertEquals(254, decoded.readableBytes());
        assertTrue(channel.isOpen());
        decoded.release();
        channel.finishAndReleaseAll();
    }

    /** Length prefixes above 127 need a two-byte VarInt, which is where naive framing breaks. */
    @Test
    void framingRoundTripsPayloadsAcrossTheVarIntWidthBoundary() {
        for (int size : new int[]{0, 1, 127, 128, 300, 5000}) {
            EmbeddedChannel encoder = new EmbeddedChannel(VarintLengthEncoder.INSTANCE);
            EmbeddedChannel decoder = new EmbeddedChannel(new VarintFrameDecoder(false));

            encoder.writeOutbound(Unpooled.wrappedBuffer(new byte[size]));
            ByteBuf framed = encoder.readOutbound();
            decoder.writeInbound(framed);

            ByteBuf decoded = decoder.readInbound();
            assertNotNull(decoded, "no frame emitted for payload size " + size);
            assertEquals(size, decoded.readableBytes(), "wrong payload size for " + size);
            decoded.release();
            encoder.finishAndReleaseAll();
            decoder.finishAndReleaseAll();
        }
    }

    @Test
    void compressionRoundTripsAboveAndBelowThreshold() {
        int threshold = 256;
        // 64 bytes stays uncompressed, 4096 highly compressible bytes does not.
        for (int size : new int[]{64, 4096}) {
            EmbeddedChannel encoder = new EmbeddedChannel(new CompressionEncoder(threshold, -1));
            EmbeddedChannel decoder = new EmbeddedChannel(new CompressionDecoder(threshold));

            byte[] payload = new byte[size];
            for (int i = 0; i < size; i++) {
                payload[i] = (byte) (i % 7);
            }

            encoder.writeOutbound(Unpooled.wrappedBuffer(payload));
            decoder.writeInbound(encoder.<ByteBuf>readOutbound());

            ByteBuf decoded = decoder.readInbound();
            assertNotNull(decoded, "nothing decoded for payload size " + size);
            byte[] result = new byte[decoded.readableBytes()];
            decoded.readBytes(result);
            assertEquals(size, result.length);
            org.junit.jupiter.api.Assertions.assertArrayEquals(payload, result);
            decoded.release();
            encoder.finishAndReleaseAll();
            decoder.finishAndReleaseAll();
        }
    }

    @Test
    void cipherRoundTripsAcrossChunkBoundaries() {
        byte[] secret = new byte[16];
        for (int i = 0; i < secret.length; i++) {
            secret[i] = (byte) i;
        }

        EmbeddedChannel encoder = new EmbeddedChannel(new CipherEncoder(
                Encryption.cipher(Cipher.ENCRYPT_MODE, secret)));
        EmbeddedChannel decoder = new EmbeddedChannel(new CipherDecoder(
                Encryption.cipher(Cipher.DECRYPT_MODE, secret)));

        // Three separate writes: CFB8 is a stream mode, so the split must not matter.
        byte[][] chunks = {{1, 2, 3}, {4, 5, 6, 7, 8}, {9}};
        StringBuilder expected = new StringBuilder();
        StringBuilder actual = new StringBuilder();

        for (byte[] chunk : chunks) {
            for (byte b : chunk) {
                expected.append(b).append(',');
            }
            encoder.writeOutbound(Unpooled.wrappedBuffer(chunk));
            decoder.writeInbound(encoder.<ByteBuf>readOutbound());
            ByteBuf decoded = decoder.readInbound();
            assertNotNull(decoded);
            while (decoded.isReadable()) {
                actual.append(decoded.readByte()).append(',');
            }
            decoded.release();
        }

        assertEquals(expected.toString(), actual.toString());
        encoder.finishAndReleaseAll();
        decoder.finishAndReleaseAll();
    }

    /**
     * The full online-mode stack in the order a real connection uses it: encryption
     * outermost, then framing, then compression.
     *
     * <p>Online mode is the one configuration the integration tests cannot reach, since
     * it needs Mojang session auth. CFB8 is a stream cipher, so a single mis-decrypted
     * byte desynchronises everything after it &mdash; and because Relay decrypts player
     * traffic and forwards it to a backend in plaintext, that corruption would surface
     * as the <em>backend</em> hanging up on garbage, not as a cipher error.
     */
    @Test
    void encryptedAndCompressedTrafficSurvivesTheFullStack() {
        byte[] secret = new byte[16];
        for (int i = 0; i < secret.length; i++) {
            secret[i] = (byte) (i * 7 + 1);
        }
        int threshold = 256;

        // Outbound order, tail to head: compression, framing, encryption.
        EmbeddedChannel sender = new EmbeddedChannel(
                new CipherEncoder(Encryption.cipher(Cipher.ENCRYPT_MODE, secret)),
                VarintLengthEncoder.INSTANCE,
                new CompressionEncoder(threshold, -1));
        EmbeddedChannel receiver = new EmbeddedChannel(
                new CipherDecoder(Encryption.cipher(Cipher.DECRYPT_MODE, secret)),
                new VarintFrameDecoder(false),
                new CompressionDecoder(threshold));

        // Sizes either side of the compression threshold, plus chunk-sized payloads, all
        // on one cipher stream so any desynchronisation shows up in the packets after it.
        int[] sizes = {1, 64, 255, 256, 257, 1024, 8192, 65536, 100, 300};
        for (int round = 0; round < sizes.length; round++) {
            int size = sizes[round];
            byte[] payload = new byte[size];
            for (int i = 0; i < size; i++) {
                payload[i] = (byte) ((i * 31 + round) % 251);
            }

            sender.writeOutbound(Unpooled.wrappedBuffer(payload));
            ByteBuf onTheWire;
            while ((onTheWire = sender.readOutbound()) != null) {
                receiver.writeInbound(onTheWire);
            }

            ByteBuf decoded = receiver.readInbound();
            assertNotNull(decoded, "nothing decoded for payload " + round + " of size " + size);
            byte[] received = new byte[decoded.readableBytes()];
            decoded.readBytes(received);
            decoded.release();

            org.junit.jupiter.api.Assertions.assertArrayEquals(payload, received,
                    "payload " + round + " (size " + size + ") was corrupted; the cipher stream has desynchronised");
        }

        sender.finishAndReleaseAll();
        receiver.finishAndReleaseAll();
    }

    @Test
    void handshakeSurvivesTheFullEncodeDecodeStack() {
        EmbeddedChannel client = clientSide(ProtocolState.HANDSHAKE);
        EmbeddedChannel proxy = proxySide(ProtocolState.HANDSHAKE);

        client.writeOutbound(new HandshakePacket(VERSION.id(), "play.example.com", 25565, 2));
        proxy.writeInbound(client.<ByteBuf>readOutbound());

        HandshakePacket decoded = proxy.readInbound();
        assertNotNull(decoded);
        assertEquals(VERSION.id(), decoded.protocolVersion());
        assertEquals("play.example.com", decoded.serverAddress());
        assertEquals(25565, decoded.port());
        assertEquals(2, decoded.nextState());

        client.finishAndReleaseAll();
        proxy.finishAndReleaseAll();
    }

    @Test
    void loginStartSurvivesTheFullEncodeDecodeStack() {
        EmbeddedChannel client = clientSide(ProtocolState.LOGIN);
        EmbeddedChannel proxy = proxySide(ProtocolState.LOGIN);

        UUID uuid = UUID.randomUUID();
        client.writeOutbound(new LoginStartPacket("Notch", uuid));
        proxy.writeInbound(client.<ByteBuf>readOutbound());

        LoginStartPacket decoded = proxy.readInbound();
        assertNotNull(decoded);
        assertEquals("Notch", decoded.username());
        assertEquals(uuid, decoded.uuid());

        client.finishAndReleaseAll();
        proxy.finishAndReleaseAll();
    }

    /**
     * Play traffic Relay has no definition for must arrive as an untouched frame,
     * including its packet id. This is the path the vast majority of bytes take.
     */
    @Test
    void unrecognisedPlayPacketsPassThroughAsRawFrames() {
        EmbeddedChannel proxy = proxySide(ProtocolState.PLAY);

        // 0x7E is not a serverbound play packet Relay registers at any version.
        ByteBuf frame = Unpooled.buffer();
        frame.writeByte(0x7E);
        frame.writeBytes(new byte[]{10, 20, 30});

        EmbeddedChannel framer = new EmbeddedChannel(VarintLengthEncoder.INSTANCE);
        framer.writeOutbound(frame);
        proxy.writeInbound(framer.<ByteBuf>readOutbound());

        Object decoded = proxy.readInbound();
        ByteBuf raw = assertInstanceOf(ByteBuf.class, decoded, "should have passed through undecoded");
        assertEquals(4, raw.readableBytes(), "the packet id must still be present");
        assertEquals(0x7E, raw.getUnsignedByte(0));

        ReferenceCountUtil.release(raw);
        framer.finishAndReleaseAll();
        proxy.finishAndReleaseAll();
    }

    /** The encoder chain a client would use: serverbound ids, then a length prefix. */
    private static EmbeddedChannel clientSide(ProtocolState state) {
        MinecraftEncoder encoder = new MinecraftEncoder(PacketDirection.SERVERBOUND);
        encoder.setState(state);
        encoder.setVersion(VERSION);
        return new EmbeddedChannel(VarintLengthEncoder.INSTANCE, encoder);
    }

    /** Relay's inbound chain for a player connection. */
    private static EmbeddedChannel proxySide(ProtocolState state) {
        MinecraftDecoder decoder = new MinecraftDecoder(PacketDirection.SERVERBOUND);
        decoder.setState(state);
        decoder.setVersion(VERSION);
        return new EmbeddedChannel(new VarintFrameDecoder(false), decoder);
    }
}
