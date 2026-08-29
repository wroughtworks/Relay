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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A realistic 1.20.2 join, with traffic flowing in <em>both</em> directions afterwards.
 *
 * <p>Every other integration test only reads backend&rarr;client play traffic. The
 * opposite direction &mdash; the settings, teleport confirmations and chunk-batch
 * acknowledgements a client starts sending the instant it enters play &mdash; had no
 * coverage at all, and that is precisely the traffic that begins about a second after
 * "joined the game" appears in a server log.
 *
 * <p>Compression is negotiated on both sides, as it is in a real deployment, so the
 * packets under test travel in compressed framing.
 */
class PlayTrafficTest {

    private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_20_2;
    private static final int THRESHOLD = 256;
    private static final int FINISH_CONFIGURATION = 0x02;

    /** Serverbound play ids a 1.20.2 client sends immediately after joining. */
    private static final int CONFIRM_TELEPORTATION = 0x00;
    private static final int CHAT_COMMAND = 0x04;
    private static final int PLAYER_SESSION = 0x06;
    private static final int CHUNK_BATCH_RECEIVED = 0x07;
    private static final int CLIENT_INFORMATION = 0x09;
    private static final int CLICK_CONTAINER = 0x0D;
    private static final int PLUGIN_MESSAGE = 0x0F;

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
    void clientPlayTrafficReachesTheBackendIntact(@TempDir Path dir) throws Exception {
        // What a vanilla client actually sends on entering play, in order. Client
        // Information is first and carries the player's settings; without it reaching the
        // backend the server never finishes bringing the player into the world.
        List<byte[]> clientPackets = List.of(
                clientInformation(),
                packet(CONFIRM_TELEPORTATION, new byte[]{0x01}),
                packet(CHUNK_BATCH_RECEIVED, new byte[]{0x42, 0x28, 0x00, 0x00}),
                // Sized and shaped like a real chat-session update: an RSA public key
                // plus a Mojang signature, so the body is effectively random. That
                // matters because it is the only packet a joining client sends that
                // crosses the compression threshold, and incompressible data is the case
                // where deflate output is *larger* than its input.
                packet(PLAYER_SESSION, incompressiblePayload(834)),
                brandMessage(),
                // Click Container sits one id below the plugin message. It is here as a
                // regression guard: Relay decodes serverbound plugin messages to claim
                // its own API channel, and an off-by-one there would make it parse this
                // as one -- corrupting a container interaction, or throwing outright.
                packet(CLICK_CONTAINER, clickContainerBody()),
                // A command is the one serverbound play packet Relay decodes rather than
                // relaying blind, so it must survive re-encoding byte for byte.
                packet(CHAT_COMMAND, chatCommandBody()));

        CompletableFuture<List<byte[]>> received = runBackend(clientPackets.size());
        Client client = joinWorld(dir);

        try {
            for (byte[] packet : clientPackets) {
                client.send(packet);
            }
            client.flush();

            List<byte[]> arrived = received.get(15, TimeUnit.SECONDS);
            assertEquals(clientPackets.size(), arrived.size(), "not every client packet reached the backend");
            for (int i = 0; i < clientPackets.size(); i++) {
                assertArrayEquals(clientPackets.get(i), arrived.get(i),
                        "client packet " + i + " (id 0x" + Integer.toHexString(clientPackets.get(i)[0] & 0xFF)
                                + ") was altered in relay");
            }

            assertTrue(client.isConnected(), "the player was disconnected while sending normal play traffic");
        } finally {
            client.close();
        }
    }

    // ------------------------------------------------------------ packet bodies

    /** Client Information: locale, view distance, chat settings, skin parts, and so on. */
    private static byte[] clientInformation() {
        ByteBuf body = Unpooled.buffer();
        ProtocolUtils.writeString(body, "en_us");
        body.writeByte(12);                     // view distance
        ProtocolUtils.writeVarInt(body, 0);     // chat mode: enabled
        body.writeBoolean(true);                // chat colours
        body.writeByte(0x7F);                   // displayed skin parts
        ProtocolUtils.writeVarInt(body, 1);     // main hand: right
        body.writeBoolean(false);               // text filtering
        body.writeBoolean(true);                // allow server listings
        return packet(CLIENT_INFORMATION, drain(body));
    }

    private static byte[] brandMessage() {
        ByteBuf body = Unpooled.buffer();
        ProtocolUtils.writeString(body, "minecraft:brand");
        ProtocolUtils.writeString(body, "vanilla");
        return packet(PLUGIN_MESSAGE, drain(body));
    }

    /**
     * A 1.20.2 chat command, which carries argument signatures after the command text.
     * Relay opens this packet, so everything past the command string has to be preserved
     * exactly &mdash; re-encoding it from parsed fields would invalidate the signatures
     * the backend is about to check.
     */
    private static byte[] chatCommandBody() {
        ByteBuf body = Unpooled.buffer();
        ProtocolUtils.writeString(body, "help");
        body.writeLong(1_700_000_000_000L);     // timestamp
        body.writeLong(0x1234_5678_9ABC_DEF0L); // salt
        ProtocolUtils.writeVarInt(body, 0);     // no argument signatures
        ProtocolUtils.writeVarInt(body, 0);     // message count
        body.writeBytes(new byte[3]);           // acknowledged bitset
        return drain(body);
    }

    /**
     * A container click. The leading window id is deliberately large: read as a string
     * length by a mis-registered plugin-message decoder, it would blow the channel-name
     * limit and drop the connection rather than failing quietly.
     */
    private static byte[] clickContainerBody() {
        ByteBuf body = Unpooled.buffer();
        ProtocolUtils.writeVarInt(body, 100_000);   // window id
        ProtocolUtils.writeVarInt(body, 7);         // state id
        body.writeShort(36);                        // slot
        body.writeByte(0);                          // button
        ProtocolUtils.writeVarInt(body, 0);         // mode
        ProtocolUtils.writeVarInt(body, 0);         // no changed slots
        body.writeBoolean(false);                   // empty carried item
        return drain(body);
    }

    /** Pseudo-random bytes: deflate cannot shrink these, and will slightly grow them. */
    private static byte[] incompressiblePayload(int size) {
        byte[] payload = new byte[size];
        new java.util.Random(size).nextBytes(payload);
        return payload;
    }

    private static byte[] largePayload(int size) {
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) {
            payload[i] = (byte) (i % 11);
        }
        return payload;
    }

    private static byte[] packet(int id, byte[] body) {
        ByteBuf buf = Unpooled.buffer();
        ProtocolUtils.writeVarInt(buf, id);
        buf.writeBytes(body);
        return drain(buf);
    }

    /** True for the {@code minecraft:register} Relay sends when a backend enters play. */
    private static boolean isChannelRegistration(byte[] packet) {
        ByteBuf buf = Unpooled.wrappedBuffer(packet);
        try {
            return ProtocolUtils.readVarInt(buf) == PLUGIN_MESSAGE
                    && ProtocolUtils.readString(buf, 256).equals("minecraft:register");
        } catch (RuntimeException e) {
            return false;
        } finally {
            buf.release();
        }
    }

    private static byte[] drain(ByteBuf buf) {
        try {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    // ------------------------------------------------------------ fake backend

    /** Plays a full server-side join, then records the client packets that arrive. */
    private CompletableFuture<List<byte[]>> runBackend(int expectedPackets) throws IOException {
        backend = new ServerSocket();
        backend.bind(new InetSocketAddress("127.0.0.1", 0));

        CompletableFuture<List<byte[]>> received = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try (Socket connection = backend.accept()) {
                connection.setSoTimeout(15_000);
                InputStream in = connection.getInputStream();
                OutputStream out = connection.getOutputStream();

                readFrame(in).release();  // handshake
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

                readCompressedFrame(in).release(); // login acknowledged

                // Registry data, then hand the player to play state.
                ByteBuf registry = Unpooled.buffer();
                registry.writeByte(0x05);
                registry.writeBytes(largePayload(6000));
                writeCompressedFrame(out, registry);
                writeCompressedFrame(out, Unpooled.buffer().writeByte(FINISH_CONFIGURATION));
                out.flush();

                readCompressedFrame(in).release(); // finish configuration acknowledged

                // Join Game and a burst of chunk data, as a real server sends at join.
                ByteBuf joinGame = Unpooled.buffer();
                joinGame.writeByte(0x29);
                joinGame.writeBytes(largePayload(512));
                writeCompressedFrame(out, joinGame);
                for (int i = 0; i < 20; i++) {
                    ByteBuf chunk = Unpooled.buffer();
                    chunk.writeByte(0x25);
                    chunk.writeBytes(largePayload(4096));
                    writeCompressedFrame(out, chunk);
                }
                out.flush();

                List<byte[]> packets = new ArrayList<>();
                while (packets.size() < expectedPackets) {
                    ByteBuf frame = readCompressedFrame(in);
                    try {
                        byte[] bytes = new byte[frame.readableBytes()];
                        frame.readBytes(bytes);
                        // Relay announces its API channels to every backend on entering
                        // play. That is proxy traffic, not the player's, so it is skipped
                        // rather than counted -- otherwise it shifts every comparison by
                        // one and the failure points at the wrong packet.
                        if (!isChannelRegistration(bytes)) {
                            packets.add(bytes);
                        }
                    } finally {
                        frame.release();
                    }
                }
                received.complete(packets);
                Thread.sleep(1000);
            } catch (Exception e) {
                received.completeExceptionally(e);
            }
        }, "fake-backend");
        thread.setDaemon(true);
        thread.start();
        return received;
    }

    // ------------------------------------------------------------ fake client

    private record Client(Socket socket) {
        void send(byte[] packet) throws IOException {
            writeCompressedFrame(socket.getOutputStream(), Unpooled.wrappedBuffer(packet));
        }

        void flush() throws IOException {
            socket.getOutputStream().flush();
        }

        boolean isConnected() {
            return socket.isConnected() && !socket.isClosed() && !socket.isInputShutdown();
        }

        void close() throws IOException {
            socket.close();
        }
    }

    /** Drives the client through login and configuration until it is in play state. */
    private Client joinWorld(Path dir) throws Exception {
        int port = freePort();
        Path config = dir.resolve("relay.toml");
        Files.writeString(config, """
                bind = "127.0.0.1:%d"
                motd = "test"
                online-mode = false
                forwarding-mode = "none"
                health.enabled = false
                control.enabled = false
                compression-threshold = %d

                [servers]
                lobby = "127.0.0.1:%d"
                """.formatted(port, THRESHOLD, backend.getLocalPort()));

        proxy = new RelayProxy(ConfigLoader.load(config));
        proxy.start();

        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(15_000);
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

        ByteBuf setCompression = readFrame(in);
        assertEquals(0x03, ProtocolUtils.readVarInt(setCompression), "expected Set Compression");
        setCompression.release();

        readCompressedFrame(in).release(); // login success

        writeCompressedFrame(out, Unpooled.buffer().writeByte(0x03)); // login acknowledged
        out.flush();

        readCompressedFrame(in).release(); // registry
        ByteBuf finish = readCompressedFrame(in);
        assertEquals(FINISH_CONFIGURATION, ProtocolUtils.readVarInt(finish), "expected Finish Configuration");
        finish.release();

        writeCompressedFrame(out, Unpooled.buffer().writeByte(FINISH_CONFIGURATION));
        out.flush();

        // Join Game plus the chunk burst.
        for (int i = 0; i < 21; i++) {
            readCompressedFrame(in).release();
        }
        return new Client(socket);
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
                out.write(drain(framed.retain()));
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
