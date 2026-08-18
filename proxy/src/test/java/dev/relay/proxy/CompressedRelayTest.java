package dev.relay.proxy;

import dev.relay.config.ConfigLoader;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The same join as {@link PlayRelayTest}, but with compression negotiated on both sides.
 *
 * <p>This is what a real session looks like and what the other integration tests leave
 * out: a live backend sends Set Compression during login, and the proxy's own player-side
 * threshold is on by default. Compression changes the framing of every subsequent packet
 * in both directions, and it is switched on mid-stream at an exact frame boundary, so
 * nothing about it is exercised by a test that never turns it on.
 *
 * <p>The registry payload is deliberately large and highly compressible, standing in for
 * the registry data a 1.20.2+ backend sends during configuration — the first packet in a
 * real join that is big enough to actually be deflated.
 */
class CompressedRelayTest {

    private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_20_2;
    private static final int THRESHOLD = 64;

    /** Configuration-state ids for 1.20.2, before the 1.20.5 cookie insertion. */
    private static final int FINISH_CONFIGURATION = 0x02;

    private RelayProxy proxy;
    private ServerSocket backend;

    @AfterEach
    void stop() throws IOException {
        if (proxy != null) {
            proxy.shutdown();
        }
        if (backend != null) {
            backend.close();
        }
    }

    @Test
    void compressedConfigurationAndPlayTrafficReachThePlayer(@TempDir Path dir) throws Exception {
        byte[] registry = new byte[8192];
        for (int i = 0; i < registry.length; i++) {
            registry[i] = (byte) (i % 13);
        }
        byte[] worldData = new byte[4096];
        for (int i = 0; i < worldData.length; i++) {
            worldData[i] = (byte) (i % 7);
        }

        CompletableFuture<Void> backendDone = runBackend(registry, worldData);

        int port = freePort();
        Path config = dir.resolve("relay.toml");
        Files.writeString(config, """
                bind = "127.0.0.1:%d"
                motd = "test"
                online-mode = false
                forwarding-mode = "none"
                health.enabled = false
                compression-threshold = %d

                [servers]
                lobby = "127.0.0.1:%d"
                """.formatted(port, THRESHOLD, backend.getLocalPort()));

        proxy = new RelayProxy(ConfigLoader.load(config));
        proxy.start();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // --- login, still uncompressed -------------------------------------
            ByteBuf handshake = Unpooled.buffer();
            handshake.writeByte(0x00);
            ProtocolUtils.writeVarInt(handshake, VERSION.id());
            ProtocolUtils.writeString(handshake, "relay.test");
            handshake.writeShort(port);
            ProtocolUtils.writeVarInt(handshake, 2);
            writeFrame(out, handshake);

            ByteBuf loginStart = Unpooled.buffer();
            loginStart.writeByte(0x00);
            ProtocolUtils.writeString(loginStart, "Tester");
            ProtocolUtils.writeUuid(loginStart, UUID.randomUUID());
            writeFrame(out, loginStart);
            out.flush();

            // Relay must announce compression before Login Success, and the threshold
            // takes effect from the very next frame.
            ByteBuf setCompression = readFrame(in);
            assertEquals(0x03, ProtocolUtils.readVarInt(setCompression), "expected Set Compression");
            assertEquals(THRESHOLD, ProtocolUtils.readVarInt(setCompression));
            setCompression.release();

            // --- everything from here is compressed framing ---------------------
            ByteBuf success = readCompressedFrame(in);
            assertEquals(0x02, ProtocolUtils.readVarInt(success), "expected Login Success");
            success.release();

            writeCompressedFrame(out, Unpooled.buffer().writeByte(0x03)); // login acknowledged
            out.flush();

            ByteBuf relayedRegistry = readCompressedFrame(in);
            try {
                assertEquals(0x05, ProtocolUtils.readVarInt(relayedRegistry), "expected the relayed registry packet");
                byte[] received = new byte[relayedRegistry.readableBytes()];
                relayedRegistry.readBytes(received);
                assertArrayEquals(registry, received,
                        "a large compressed configuration packet was corrupted in relay");
            } finally {
                relayedRegistry.release();
            }

            ByteBuf finish = readCompressedFrame(in);
            assertEquals(FINISH_CONFIGURATION, ProtocolUtils.readVarInt(finish));
            finish.release();

            writeCompressedFrame(out, Unpooled.buffer().writeByte(FINISH_CONFIGURATION));
            out.flush();

            ByteBuf play = readCompressedFrame(in);
            try {
                assertEquals(0x24, ProtocolUtils.readVarInt(play), "expected the relayed play packet");
                byte[] received = new byte[play.readableBytes()];
                play.readBytes(received);
                assertArrayEquals(worldData, received, "compressed world data was corrupted in relay");
            } finally {
                play.release();
            }

            // A short packet stays uncompressed even after the threshold is negotiated,
            // so both branches of the codec are on the same connection.
            ByteBuf small = readCompressedFrame(in);
            try {
                assertEquals(0x1F, ProtocolUtils.readVarInt(small), "expected the small play packet");
                assertEquals(4, small.readableBytes());
            } finally {
                small.release();
            }
        }

        backendDone.get(15, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------ fake backend

    private CompletableFuture<Void> runBackend(byte[] registry, byte[] worldData) throws IOException {
        backend = new ServerSocket();
        backend.bind(new InetSocketAddress("127.0.0.1", 0));

        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try (Socket connection = backend.accept()) {
                connection.setSoTimeout(15_000);
                InputStream in = connection.getInputStream();
                OutputStream out = connection.getOutputStream();

                readFrame(in).release(); // handshake
                ByteBuf loginStart = readFrame(in);
                ProtocolUtils.readVarInt(loginStart);
                String name = ProtocolUtils.readString(loginStart);
                UUID uuid = ProtocolUtils.readUuid(loginStart);
                loginStart.release();

                // Announce compression first, exactly as a real backend does.
                ByteBuf setCompression = Unpooled.buffer();
                setCompression.writeByte(0x03);
                ProtocolUtils.writeVarInt(setCompression, THRESHOLD);
                writeFrame(out, setCompression);
                out.flush();

                ByteBuf success = Unpooled.buffer();
                success.writeByte(0x02);
                ProtocolUtils.writeUuid(success, uuid);
                ProtocolUtils.writeString(success, name);
                ProtocolUtils.writeVarInt(success, 0);
                writeCompressedFrame(out, success);
                out.flush();

                readCompressedFrame(in).release(); // login acknowledged

                ByteBuf registryPacket = Unpooled.buffer();
                registryPacket.writeByte(0x05);
                registryPacket.writeBytes(registry);
                writeCompressedFrame(out, registryPacket);

                writeCompressedFrame(out, Unpooled.buffer().writeByte(FINISH_CONFIGURATION));
                out.flush();

                readCompressedFrame(in).release(); // finish configuration acknowledged

                ByteBuf playPacket = Unpooled.buffer();
                playPacket.writeByte(0x24);
                playPacket.writeBytes(worldData);
                writeCompressedFrame(out, playPacket);

                writeCompressedFrame(out, Unpooled.buffer().writeByte(0x1F).writeInt(42));
                out.flush();

                Thread.sleep(2500);
                done.complete(null);
            } catch (Exception e) {
                done.completeExceptionally(e);
            }
        }, "fake-backend");
        thread.setDaemon(true);
        thread.start();
        return done;
    }

    // ------------------------------------------------------------ framing helpers

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private static void writeFrame(OutputStream out, ByteBuf payload) throws IOException {
        try {
            ByteBuf framed = Unpooled.buffer();
            try {
                ProtocolUtils.writeVarInt(framed, payload.readableBytes());
                framed.writeBytes(payload);
                byte[] bytes = new byte[framed.readableBytes()];
                framed.readBytes(bytes);
                out.write(bytes);
            } finally {
                framed.release();
            }
        } finally {
            payload.release();
        }
    }

    /**
     * Writes a frame in compressed format: total length, then either a zero marker and
     * raw bytes, or the uncompressed size and a zlib stream.
     */
    private static void writeCompressedFrame(OutputStream out, ByteBuf payload) throws IOException {
        try {
            int size = payload.readableBytes();
            byte[] body = new byte[size];
            payload.readBytes(body);

            ByteBuf inner = Unpooled.buffer();
            try {
                if (size < THRESHOLD) {
                    ProtocolUtils.writeVarInt(inner, 0);
                    inner.writeBytes(body);
                } else {
                    ProtocolUtils.writeVarInt(inner, size);
                    inner.writeBytes(deflate(body));
                }
                writeFrame(out, inner.retain());
            } finally {
                inner.release();
            }
        } finally {
            payload.release();
        }
    }

    private static ByteBuf readCompressedFrame(InputStream in) throws IOException {
        ByteBuf frame = readFrame(in);
        try {
            int uncompressedSize = ProtocolUtils.readVarInt(frame);
            byte[] body = new byte[frame.readableBytes()];
            frame.readBytes(body);
            return Unpooled.wrappedBuffer(uncompressedSize == 0 ? body : inflate(body, uncompressedSize));
        } finally {
            frame.release();
        }
    }

    private static byte[] deflate(byte[] input) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(input);
            deflater.finish();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) {
                output.write(chunk, 0, deflater.deflate(chunk));
            }
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] input, int expectedSize) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(input);
            byte[] output = new byte[expectedSize];
            int total = 0;
            while (total < expectedSize && !inflater.finished()) {
                int produced = inflater.inflate(output, total, expectedSize - total);
                if (produced == 0) {
                    break;
                }
                total += produced;
            }
            if (total != expectedSize) {
                throw new IOException("inflated to " + total + " bytes, expected " + expectedSize);
            }
            return output;
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("malformed compressed frame", e);
        } finally {
            inflater.end();
        }
    }

    private static ByteBuf readFrame(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int length = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte b = data.readByte();
            length |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
        }
        byte[] payload = new byte[length];
        data.readFully(payload);
        return Unpooled.wrappedBuffer(payload);
    }
}
