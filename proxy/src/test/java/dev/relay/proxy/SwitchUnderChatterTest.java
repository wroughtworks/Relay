package dev.relay.proxy;

import dev.relay.config.ConfigLoader;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import dev.relay.testing.Frames;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * A switch away from a backend that never stops talking.
 *
 * <p>{@code ServerSwitchTest} passes, and passed throughout the weeks a real client was
 * being disconnected mid-switch with {@code IndexOutOfBoundsException: Index 37 out of
 * bounds for length 9}. Its fake backend sends one marker and goes quiet. A real Paper
 * server never goes quiet, and that difference was the entire bug.
 *
 * <p>The rule being checked is a timing rule the protocol never states outright. A client
 * enters configuration state the instant it <em>sends</em> its acknowledgement, which is a
 * network round trip before the proxy can know. So from the moment Start Configuration
 * goes out, nothing from the old backend may reach the client: it would be decoded against
 * a state the client has already left.
 *
 * <p>Written as "this must not arrive after that", because a test that merely skips what
 * it is not looking for cannot see an ordering violation at all &mdash; which is exactly
 * how this went unnoticed.
 */
class SwitchUnderChatterTest {

    private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_20_2;

    private static final int SB_PLAY_CHAT_COMMAND = 0x04;
    private static final int SB_PLAY_CONFIG_ACK = 0x0B;
    private static final int CB_PLAY_START_CONFIGURATION = 0x65;
    private static final int CB_CONFIG_REGISTRY = 0x05;
    private static final int CB_CONFIG_FINISH = 0x02;
    private static final int SB_CONFIG_FINISH_ACK = 0x02;
    private static final int CB_LOGIN_SUCCESS = 0x02;
    private static final int SB_LOGIN_ACK = 0x03;

    /**
     * How long the client waits before acknowledging.
     *
     * <p>Stands in for the round trip a real client's reply takes. The bug lives entirely
     * inside this window, so a test that closes it instantly cannot see the bug.
     */
    private static final long ACK_DELAY_MILLIS = 300;

    /** Lobby's play chatter: the packets a real server keeps sending regardless. */
    private static final int LOBBY_CHATTER = 0x25;
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
    void theOldBackendGoesSilentTheMomentTheSwitchIsRequested(@TempDir Path dir) throws Exception {
        Backend lobby = new Backend("lobby", LOBBY_CHATTER, true);
        Backend survival = new Backend("survival", SURVIVAL_MARKER, false);
        CompletableFuture<Void> lobbyReady = lobby.serve();
        CompletableFuture<Void> survivalReady = survival.serve();

        int port = Frames.freePort();
        writeConfig(dir, port, lobby.port(), survival.port());
        proxy = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        proxy.start();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            login(out, in, port);
            lobbyReady.get(15, TimeUnit.SECONDS);
            assertNotNull(Frames.readUntil(in, LOBBY_CHATTER), "expected play traffic from lobby");

            writeFrame(out, chatCommand("server survival"));
            ByteBuf start = Frames.readUntil(in, CB_PLAY_START_CONFIGURATION);
            assertNotNull(start, "Relay never asked the client to leave play state");
            start.release();

            // A pause before acknowledging, because a real client's reply takes a network
            // round trip to arrive. Without it this test acknowledges within a millisecond
            // and the window is too narrow for the old backend to say anything -- which is
            // exactly why the bug survived a suite that already drove a full switch.
            Thread.sleep(ACK_DELAY_MILLIS);

            // From the client's own point of view it is now decoding as configuration,
            // and lobby has been chattering throughout.
            writeFrame(out, Unpooled.buffer().writeByte(SB_PLAY_CONFIG_ACK));

            // The assertion that matters: the next thing to arrive must be the new
            // backend's configuration, not another play packet from the old one.
            int arrived = Frames.readUntilEither(in, CB_CONFIG_REGISTRY, LOBBY_CHATTER);
            assertNotEquals(LOBBY_CHATTER, arrived,
                    "a play packet from the old backend reached a client that had already "
                            + "entered configuration state; that is what a player sees as "
                            + "'DecoderException: Index 37 out of bounds for length 9'");
            assertEquals(CB_CONFIG_REGISTRY, arrived, "the new backend never configured the player");

            ByteBuf finish = Frames.readUntil(in, CB_CONFIG_FINISH);
            assertNotNull(finish, "the new backend never finished configuring");
            finish.release();
            writeFrame(out, Unpooled.buffer().writeByte(SB_CONFIG_FINISH_ACK));

            survivalReady.get(15, TimeUnit.SECONDS);
            assertNotNull(Frames.readUntil(in, SURVIVAL_MARKER), "no play data after the switch");
        }
    }

    private static void writeConfig(Path dir, int port, int lobbyPort, int survivalPort)
            throws IOException {
        Files.writeString(dir.resolve("relay.toml"), """
                bind = "127.0.0.1:%d"
                motd = "test"
                online-mode = false
                forwarding-mode = "none"
                health.enabled = false
                control.enabled = false
                compression-threshold = -1
                try = ["lobby"]

                [servers]
                lobby = "127.0.0.1:%d"
                survival = "127.0.0.1:%d"

                [permissions]
                default = ["relay.command.server"]
                """.formatted(port, lobbyPort, survivalPort));
    }

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

        Frames.read(in).release();
        writeFrame(out, Unpooled.buffer().writeByte(SB_LOGIN_ACK));
        Frames.readUntil(in, CB_CONFIG_REGISTRY).release();
        Frames.readUntil(in, CB_CONFIG_FINISH).release();
        writeFrame(out, Unpooled.buffer().writeByte(SB_CONFIG_FINISH_ACK));
    }

    private static ByteBuf chatCommand(String command) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(SB_PLAY_CHAT_COMMAND);
        ProtocolUtils.writeString(buf, command);
        buf.writeLong(1_700_000_000_000L);
        buf.writeLong(0L);
        ProtocolUtils.writeVarInt(buf, 0);
        ProtocolUtils.writeVarInt(buf, 0);
        buf.writeBytes(new byte[3]);
        return buf;
    }

    private static void writeFrame(OutputStream out, ByteBuf payload) throws IOException {
        Frames.write(out, payload);
    }

    /** A backend that behaves like a real one: it keeps talking until it is cut off. */
    private final class Backend {

        private final String name;
        private final int marker;
        private final boolean chatty;
        private final ServerSocket socket;

        Backend(String name, int marker, boolean chatty) throws IOException {
            this.name = name;
            this.marker = marker;
            this.chatty = chatty;
            this.socket = new ServerSocket();
            this.socket.bind(new InetSocketAddress("127.0.0.1", 0));
            sockets.add(this.socket);
        }

        int port() {
            return socket.getLocalPort();
        }

        CompletableFuture<Void> serve() {
            CompletableFuture<Void> ready = new CompletableFuture<>();
            Thread thread = new Thread(() -> {
                try (Socket connection = socket.accept()) {
                    connection.setSoTimeout(20_000);
                    InputStream in = connection.getInputStream();
                    OutputStream out = connection.getOutputStream();

                    Frames.read(in).release();                       // handshake
                    ByteBuf loginStart = Frames.read(in);
                    ProtocolUtils.readVarInt(loginStart);
                    String username = ProtocolUtils.readString(loginStart);
                    UUID uuid = ProtocolUtils.readUuid(loginStart);
                    loginStart.release();

                    ByteBuf success = Unpooled.buffer();
                    success.writeByte(CB_LOGIN_SUCCESS);
                    ProtocolUtils.writeUuid(success, uuid);
                    ProtocolUtils.writeString(success, username);
                    ProtocolUtils.writeVarInt(success, 0);
                    Frames.write(out, success);

                    Frames.read(in).release();                       // login acknowledged

                    ByteBuf registry = Unpooled.buffer();
                    registry.writeByte(CB_CONFIG_REGISTRY);
                    registry.writeBytes(new byte[64]);
                    Frames.write(out, registry);
                    Frames.write(out, Unpooled.buffer().writeByte(CB_CONFIG_FINISH));

                    Frames.read(in).release();                       // finish configuration ack

                    ByteBuf play = Unpooled.buffer();
                    play.writeByte(marker);
                    play.writeBytes(new byte[32]);
                    Frames.write(out, play);
                    ready.complete(null);

                    if (!chatty) {
                        while (in.read() != -1) {
                            // Held open until the proxy lets go.
                        }
                        return;
                    }

                    // The difference that matters. A real server keeps sending world
                    // updates right through a switch, because nothing tells it one is
                    // happening -- the proxy is the only party that knows.
                    while (!Thread.currentThread().isInterrupted()) {
                        ByteBuf chatter = Unpooled.buffer();
                        chatter.writeByte(marker);
                        chatter.writeBytes(new byte[32]);
                        Frames.write(out, chatter);
                        Thread.sleep(5);
                    }
                } catch (Exception expected) {
                    // The proxy closed this backend, which is how a switch ends for the
                    // side being left.
                    ready.complete(null);
                }
            }, "chatty-backend-" + name);
            thread.setDaemon(true);
            thread.start();
            return ready;
        }
    }
}
