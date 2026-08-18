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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A backend flooding the proxy faster than the player can drain it.
 *
 * <p>Chunk packets at join are tens of kilobytes each &mdash; a single one is close to
 * Netty's default 64&nbsp;KB write-buffer high-water mark, so the player's channel goes
 * unwritable almost immediately and Relay pauses reading from the backend. If reading is
 * ever paused without being resumed, the proxy stops draining the backend socket, the
 * backend's writes pile up behind a zero-window TCP connection, and the session dies with
 * nothing wrong on the wire.
 *
 * <p>The client here reads deliberately slowly to hold the pause open.
 */
class BackpressureTest {

    private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_20_2;
    private static final int THRESHOLD = 256;
    private static final int FINISH_CONFIGURATION = 0x02;

    /** Comfortably past the write-buffer high-water mark once a few are queued. */
    private static final int CHUNK_SIZE = 50_000;
    private static final int CHUNK_COUNT = 60;

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
    void aSlowPlayerDoesNotStallTheBackendConnection(@TempDir Path dir) throws Exception {
        CompletableFuture<Boolean> backendHealthy = runBackend();
        Socket client = joinWorld(dir);

        try {
            InputStream in = client.getInputStream();
            long totalBytes = 0;

            for (int i = 0; i < CHUNK_COUNT; i++) {
                ByteBuf chunk = readCompressedFrame(in);
                try {
                    assertEquals(0x25, ProtocolUtils.readVarInt(chunk), "expected a chunk packet, index " + i);
                    byte[] body = new byte[chunk.readableBytes()];
                    chunk.readBytes(body);
                    assertEquals(CHUNK_SIZE, body.length, "chunk " + i + " was truncated in relay");
                    assertArrayEquals(payload(i), body, "chunk " + i + " was corrupted in relay");
                    totalBytes += body.length;
                } finally {
                    chunk.release();
                }

                // Drain slower than the backend fills, keeping the proxy's write buffer
                // over its high-water mark and the read pause engaged.
                if (i % 10 == 0) {
                    Thread.sleep(40);
                }
            }

            assertEquals((long) CHUNK_SIZE * CHUNK_COUNT, totalBytes, "not all chunk data arrived");
            assertTrue(backendHealthy.get(30, TimeUnit.SECONDS),
                    "the backend connection was closed while the player was catching up");
        } finally {
            client.close();
        }
    }

    private static byte[] payload(int index) {
        byte[] payload = new byte[CHUNK_SIZE];
        for (int i = 0; i < CHUNK_SIZE; i++) {
            payload[i] = (byte) ((i * 17 + index) % 241);
        }
        return payload;
    }

    /** Completes true if the backend wrote everything without the socket dying. */
    private CompletableFuture<Boolean> runBackend() throws IOException {
        backend = new ServerSocket();
        backend.bind(new InetSocketAddress("127.0.0.1", 0));

        CompletableFuture<Boolean> healthy = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try (Socket connection = backend.accept()) {
                connection.setSoTimeout(30_000);
                InputStream in = connection.getInputStream();
                OutputStream out = connection.getOutputStream();

                readFrame(in).release();
                ByteBuf loginStart = readFrame(in);
                ProtocolUtils.readVarInt(loginStart);
                String name = ProtocolUtils.readString(loginStart);
                UUID uuid = ProtocolUtils.readUuid(loginStart);
                loginStart.release();

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

                readCompressedFrame(in).release();
                writeCompressedFrame(out, Unpooled.buffer().writeByte(FINISH_CONFIGURATION));
                out.flush();
                readCompressedFrame(in).release();

                // Blast chunks with no pauses, exactly as a server does at join.
                for (int i = 0; i < CHUNK_COUNT; i++) {
                    ByteBuf chunk = Unpooled.buffer();
                    chunk.writeByte(0x25);
                    chunk.writeBytes(payload(i));
                    writeCompressedFrame(out, chunk);
                }
                out.flush();

                // Stay up long enough for the player to finish draining. If Relay stops
                // reading, this socket dies or the write above blocks forever.
                Thread.sleep(8000);
                healthy.complete(connection.isConnected() && !connection.isClosed());
            } catch (Exception e) {
                healthy.completeExceptionally(e);
            }
        }, "fake-backend");
        thread.setDaemon(true);
        thread.start();
        return healthy;
    }

    private Socket joinWorld(Path dir) throws Exception {
        int port = freePort();
        Path config = dir.resolve("relay.toml");
        Files.writeString(config, """
                bind = "127.0.0.1:%d"
                motd = "test"
                online-mode = false
                forwarding-mode = "none"
                health.enabled = false
                compression-threshold = %d
                read-timeout = 60000

                [servers]
                lobby = "127.0.0.1:%d"
                """.formatted(port, THRESHOLD, backend.getLocalPort()));

        proxy = new RelayProxy(ConfigLoader.load(config));
        proxy.start();

        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(30_000);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

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

        readFrame(in).release();            // set compression
        readCompressedFrame(in).release();  // login success
        writeCompressedFrame(out, Unpooled.buffer().writeByte(0x03));
        out.flush();
        readCompressedFrame(in).release();  // finish configuration
        writeCompressedFrame(out, Unpooled.buffer().writeByte(FINISH_CONFIGURATION));
        out.flush();
        return socket;
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

    private static void writeCompressedFrame(OutputStream out, ByteBuf payload) throws IOException {
        try {
            int size = payload.readableBytes();
            byte[] body = new byte[size];
            payload.readBytes(body);

            ByteBuf inner = Unpooled.buffer();
            if (size < THRESHOLD) {
                ProtocolUtils.writeVarInt(inner, 0);
                inner.writeBytes(body);
            } else {
                ProtocolUtils.writeVarInt(inner, size);
                inner.writeBytes(deflate(body));
            }
            writeFrame(out, inner);
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
