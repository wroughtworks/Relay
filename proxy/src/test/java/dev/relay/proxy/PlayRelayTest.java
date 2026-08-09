package dev.relay.proxy;

import dev.relay.config.ConfigLoader;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carries a player all the way through to play state and checks that world data actually
 * reaches them.
 *
 * <p>Every other integration test stops at login or configuration. That left the single
 * busiest path in the proxy — opaque backend frames being relayed to the player — with no
 * coverage at all, which is precisely where a client sits forever on "Joining world":
 * login and configuration both completed, so nothing looks wrong, but no play packet ever
 * arrives.
 */
class PlayRelayTest {

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

    /**
     * Run at both sides of the 1.20.5 configuration-state id shift, since the packet ids
     * driving the handover differ across it.
     */
    @ParameterizedTest
    @ValueSource(ints = {764, 769})
    void backendPlayTrafficReachesThePlayer(int protocol, @TempDir Path dir) throws Exception {
        ProtocolVersion version = ProtocolVersion.byId(protocol);
        int finishConfig = version.atLeast(ProtocolVersion.MINECRAFT_1_20_5) ? 0x03 : 0x02;

        // A distinctive payload standing in for Join Game, so its arrival is unambiguous.
        byte[] worldData = new byte[]{0x2B, 1, 2, 3, 4, 5, 6, 7, 8, 9};

        CompletableFuture<Void> backendDone = runBackend(finishConfig, worldData);
        Client client = connectAndPlay(dir, protocol, finishConfig);
        backendDone.get(15, TimeUnit.SECONDS);

        try {
            ByteBuf play = client.readFrame();
            try {
                byte[] received = new byte[play.readableBytes()];
                play.readBytes(received);
                assertArrayEquals(worldData, received,
                        "the backend's play packet must reach the player byte for byte");
            } finally {
                play.release();
            }

            // A single frame could pass by luck; the real stream is thousands of them.
            for (int i = 0; i < 50; i++) {
                ByteBuf next = client.readFrame();
                try {
                    assertEquals(i, next.getUnsignedByte(1), "frame " + i + " arrived out of order or not at all");
                } finally {
                    next.release();
                }
            }
        } finally {
            client.close();
        }
    }

    // ------------------------------------------------------------ fake backend

    /** Plays the server side of a login, a configuration phase, and then sends world data. */
    private CompletableFuture<Void> runBackend(int finishConfigId, byte[] worldData) throws IOException {
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

                ByteBuf success = Unpooled.buffer();
                success.writeByte(0x02);
                ProtocolUtils.writeUuid(success, uuid);
                ProtocolUtils.writeString(success, name);
                ProtocolUtils.writeVarInt(success, 0); // no properties
                writeFrame(out, success);
                out.flush();

                readFrame(in).release(); // login acknowledged

                // A configuration packet Relay has no definition for, as registry data
                // would be. It must be relayed untouched.
                ByteBuf registry = Unpooled.buffer();
                registry.writeByte(0x07);
                registry.writeBytes(new byte[]{(byte) 0xAA, (byte) 0xBB});
                writeFrame(out, registry);

                writeFrame(out, Unpooled.buffer().writeByte(finishConfigId));
                out.flush();

                readFrame(in).release(); // finish configuration acknowledged -- now in play

                writeFrame(out, Unpooled.wrappedBuffer(worldData));
                for (int i = 0; i < 50; i++) {
                    writeFrame(out, Unpooled.buffer().writeByte(0x2B).writeByte(i));
                }
                out.flush();

                // Hold the socket open; closing would kick the player before the test reads.
                Thread.sleep(3000);
                done.complete(null);
            } catch (Exception e) {
                done.completeExceptionally(e);
            }
        }, "fake-backend");
        thread.setDaemon(true);
        thread.start();
        return done;
    }

    // ------------------------------------------------------------ fake client

    private record Client(Socket socket) {
        ByteBuf readFrame() throws IOException {
            return PlayRelayTest.readFrame(socket.getInputStream());
        }

        void close() throws IOException {
            socket.close();
        }
    }

    /** Drives the client side up to the point where play traffic should start arriving. */
    private Client connectAndPlay(Path dir, int protocol, int finishConfigId) throws Exception {
        int port = freePort();
        Path config = dir.resolve("relay.toml");
        Files.writeString(config, """
                bind = "127.0.0.1:%d"
                motd = "test"
                online-mode = false
                forwarding-mode = "none"
                compression-threshold = -1

                [servers]
                lobby = "127.0.0.1:%d"
                """.formatted(port, backend.getLocalPort()));

        proxy = new RelayProxy(ConfigLoader.load(config));
        proxy.start();

        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(15_000);
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        ByteBuf handshake = Unpooled.buffer();
        handshake.writeByte(0x00);
        ProtocolUtils.writeVarInt(handshake, protocol);
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

        readFrame(in).release(); // login success
        writeFrame(out, Unpooled.buffer().writeByte(0x03)); // login acknowledged
        out.flush();

        // Relay the backend's configuration sequence until it says it is finished.
        ByteBuf registry = readFrame(in);
        try {
            assertEquals(0x07, ProtocolUtils.readVarInt(registry), "expected the relayed registry packet");
            assertTrue(registry.isReadable(), "the relayed configuration payload was lost");
        } finally {
            registry.release();
        }

        ByteBuf finish = readFrame(in);
        try {
            assertEquals(finishConfigId, ProtocolUtils.readVarInt(finish), "expected finish configuration");
        } finally {
            finish.release();
        }

        writeFrame(out, Unpooled.buffer().writeByte(finishConfigId)); // acknowledge
        out.flush();

        return new Client(socket);
    }

    // ------------------------------------------------------------ helpers

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
