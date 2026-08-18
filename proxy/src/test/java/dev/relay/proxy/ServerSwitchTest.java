package dev.relay.proxy;

import dev.relay.config.ConfigLoader;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A complete {@code /server} switch, driven over real sockets.
 *
 * <p>Switching is the most intricate sequence in the proxy and, until this test, the only
 * major path with no coverage at all. It spans four handlers and both connections: the
 * player is asked to leave play state, acknowledges, is handed to a second backend, walks
 * that backend's configuration phase, and re-enters play &mdash; while the first backend
 * is detached without the client noticing.
 *
 * <p>Packet ids are written literally rather than read from {@code StateRegistry}, so the
 * test states what the 1.20.2 protocol requires instead of agreeing with whatever the
 * registry happens to hold.
 */
class ServerSwitchTest {

    private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_20_2;

    // --- 1.20.2 ids the switch depends on ---
    private static final int SB_PLAY_CHAT_COMMAND = 0x04;
    private static final int SB_PLAY_CONFIG_ACK = 0x0B;
    private static final int CB_PLAY_START_CONFIGURATION = 0x65;
    private static final int CB_CONFIG_REGISTRY = 0x05;
    private static final int CB_CONFIG_FINISH = 0x02;
    private static final int SB_CONFIG_FINISH_ACK = 0x02;
    private static final int CB_LOGIN_SUCCESS = 0x02;
    private static final int SB_LOGIN_ACK = 0x03;

    /** Distinctive clientbound play ids, so it is unambiguous which backend sent what. */
    private static final int LOBBY_MARKER = 0x25;
    private static final int SURVIVAL_MARKER = 0x24;

    private RelayProxy proxy;
    private final List<ServerSocket> sockets = new ArrayList<>();

    @AfterEach
    void stop() throws IOException {
        if (proxy != null) {
            proxy.shutdown();
        }
        for (ServerSocket socket : sockets) {
            socket.close();
        }
    }

    @Test
    void aPlayerCanBeSwitchedBetweenBackends(@TempDir Path dir) throws Exception {
        Backend lobby = new Backend("lobby", LOBBY_MARKER);
        Backend survival = new Backend("survival", SURVIVAL_MARKER);

        CompletableFuture<Void> lobbyReady = lobby.serve();
        CompletableFuture<Void> survivalReady = survival.serve();

        int port = freePort();
        writeConfig(dir, port, lobby.port(), survival.port());

        proxy = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        proxy.start();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // ---------------------------------------------------- join lobby
            login(out, in, port);
            lobbyReady.get(15, TimeUnit.SECONDS);

            ByteBuf world = readUntilId(in, LOBBY_MARKER);
            assertNotNull(world, "expected play data from lobby");
            world.release();

            // ---------------------------------------------------- ask to switch
            writeFrame(out, chatCommand("server survival"));
            out.flush();

            // Relay must ask the client to leave play state. Anything before that is
            // ordinary lobby traffic and is skipped.
            ByteBuf startConfiguration = readUntilId(in, CB_PLAY_START_CONFIGURATION);
            assertNotNull(startConfiguration, "Relay never sent Start Configuration");
            startConfiguration.release();

            // ---------------------------------------------------- agree to it
            writeFrame(out, Unpooled.buffer().writeByte(SB_PLAY_CONFIG_ACK));
            out.flush();

            // The new backend's configuration must now be relayed, ending in Finish.
            ByteBuf registry = readUntilId(in, CB_CONFIG_REGISTRY);
            assertNotNull(registry, "survival's registry data never reached the player");
            assertArrayEquals(payload("survival"), drain(registry),
                    "survival's registry data was corrupted in relay");

            ByteBuf finish = readUntilId(in, CB_CONFIG_FINISH);
            assertNotNull(finish, "survival never finished configuring the player");
            finish.release();

            writeFrame(out, Unpooled.buffer().writeByte(SB_CONFIG_FINISH_ACK));
            out.flush();

            // Only now can survival finish: it blocks on this acknowledgement before
            // sending any play traffic, exactly as a real server does.
            survivalReady.get(15, TimeUnit.SECONDS);

            // ---------------------------------------------------- playing on survival
            ByteBuf survivalWorld = readUntilId(in, SURVIVAL_MARKER);
            assertNotNull(survivalWorld, "no play data arrived from survival after the switch");
            assertArrayEquals(payload("survival"), drain(survivalWorld),
                    "survival's play data was corrupted in relay");

            assertEquals(1, survival.playersSeen(), "survival should have been logged into once");
            assertTrue(lobby.awaitDisconnect(10), "the old backend connection should have been dropped");
        }
    }

    private static void writeConfig(Path dir, int port, int lobbyPort, int survivalPort) throws IOException {
        Files.writeString(dir.resolve("relay.toml"), """
                bind = "127.0.0.1:%d"
                motd = "test"
                online-mode = false
                forwarding-mode = "none"
                health.enabled = false
                compression-threshold = -1
                try = ["lobby"]

                [servers]
                lobby = "127.0.0.1:%d"
                survival = "127.0.0.1:%d"

                [permissions]
                default = ["relay.command.server"]
                """.formatted(port, lobbyPort, survivalPort));
    }

    // ------------------------------------------------------------ client side

    private void login(OutputStream out, InputStream in, int port) throws IOException {
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

        readFrame(in).release();                                   // login success
        writeFrame(out, Unpooled.buffer().writeByte(SB_LOGIN_ACK));
        out.flush();

        ByteBuf registry = readUntilId(in, CB_CONFIG_REGISTRY);
        assertNotNull(registry, "lobby's registry data never arrived");
        registry.release();

        ByteBuf finish = readUntilId(in, CB_CONFIG_FINISH);
        assertNotNull(finish, "lobby never finished configuring the player");
        finish.release();

        writeFrame(out, Unpooled.buffer().writeByte(SB_CONFIG_FINISH_ACK));
        out.flush();
    }

    /** An unsigned 1.20.2 chat command: the text, then fields Relay keeps opaque. */
    private static ByteBuf chatCommand(String command) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(SB_PLAY_CHAT_COMMAND);
        ProtocolUtils.writeString(buf, command);
        buf.writeLong(1_700_000_000_000L);   // timestamp
        buf.writeLong(0L);                   // salt
        ProtocolUtils.writeVarInt(buf, 0);   // no argument signatures
        ProtocolUtils.writeVarInt(buf, 0);   // message count
        buf.writeBytes(new byte[3]);         // acknowledged bitset
        return buf;
    }

    /** Reads frames until one carries {@code id}, discarding the rest. Returns its body. */
    private static ByteBuf readUntilId(InputStream in, int id) throws IOException {
        for (int i = 0; i < 500; i++) {
            ByteBuf frame = readFrame(in);
            try {
                if (ProtocolUtils.readVarInt(frame.duplicate()) == id) {
                    ByteBuf body = frame.copy();
                    ProtocolUtils.readVarInt(body);
                    return body;
                }
            } finally {
                frame.release();
            }
        }
        return null;
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

    private static byte[] payload(String name) {
        byte[] bytes = new byte[512];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (name.charAt(i % name.length()) + i);
        }
        return bytes;
    }

    /** A server that completes login and configuration, then sends one marked packet. */
    private final class Backend {

        private final String name;
        private final int marker;
        private final ServerSocket socket;
        private final CompletableFuture<Void> disconnected = new CompletableFuture<>();
        private volatile int playersSeen;

        Backend(String name, int marker) throws IOException {
            this.name = name;
            this.marker = marker;
            this.socket = new ServerSocket();
            this.socket.bind(new InetSocketAddress("127.0.0.1", 0));
            sockets.add(this.socket);
        }

        int port() {
            return socket.getLocalPort();
        }

        int playersSeen() {
            return playersSeen;
        }

        boolean awaitDisconnect(long seconds) {
            try {
                disconnected.get(seconds, TimeUnit.SECONDS);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Completes when a player has been taken through to play state. */
        CompletableFuture<Void> serve() {
            CompletableFuture<Void> ready = new CompletableFuture<>();
            Thread thread = new Thread(() -> {
                try (Socket connection = socket.accept()) {
                    connection.setSoTimeout(20_000);
                    InputStream in = connection.getInputStream();
                    OutputStream out = connection.getOutputStream();

                    readFrame(in).release();                       // handshake
                    ByteBuf loginStart = readFrame(in);
                    ProtocolUtils.readVarInt(loginStart);
                    String username = ProtocolUtils.readString(loginStart);
                    UUID uuid = ProtocolUtils.readUuid(loginStart);
                    loginStart.release();
                    playersSeen++;

                    ByteBuf success = Unpooled.buffer();
                    success.writeByte(CB_LOGIN_SUCCESS);
                    ProtocolUtils.writeUuid(success, uuid);
                    ProtocolUtils.writeString(success, username);
                    ProtocolUtils.writeVarInt(success, 0);
                    writeFrame(out, success);
                    out.flush();

                    readFrame(in).release();                       // login acknowledged

                    ByteBuf registry = Unpooled.buffer();
                    registry.writeByte(CB_CONFIG_REGISTRY);
                    registry.writeBytes(payload(name));
                    writeFrame(out, registry);
                    writeFrame(out, Unpooled.buffer().writeByte(CB_CONFIG_FINISH));
                    out.flush();

                    readFrame(in).release();                       // finish configuration ack

                    ByteBuf play = Unpooled.buffer();
                    play.writeByte(marker);
                    play.writeBytes(payload(name));
                    writeFrame(out, play);
                    out.flush();
                    ready.complete(null);

                    // Stay up until the proxy drops us, which is how a switch away from
                    // this backend presents on the losing side.
                    try {
                        while (in.read() != -1) {
                            // draining until the proxy closes the connection
                        }
                    } catch (IOException expected) {
                        // The proxy closed it, which is the outcome under test.
                    }
                    disconnected.complete(null);
                } catch (Exception e) {
                    ready.completeExceptionally(e);
                    disconnected.completeExceptionally(e);
                }
            }, "fake-backend-" + name);
            thread.setDaemon(true);
            thread.start();
            return ready;
        }
    }

    // ------------------------------------------------------------ framing

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
