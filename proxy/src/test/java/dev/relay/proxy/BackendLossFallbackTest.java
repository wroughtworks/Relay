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
import java.nio.charset.StandardCharsets;
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
 * What happens to a player when the backend they are playing on dies.
 *
 * <p>The interesting case is the one that looks impossible: the player is mid-game and
 * the server holding their session has gone. Relay's answer is that the client is still
 * perfectly healthy and still in play state, which is exactly where a {@code /server}
 * switch starts &mdash; so the rescue is a switch whose old side happens to be a corpse.
 *
 * <p>This is worth a test of its own rather than a variation on {@code ServerSwitchTest}
 * because the failure mode is silent and expensive: without it, restarting one backend
 * returns everyone on it to the multiplayer menu, and nothing in the logs calls that a
 * bug.
 *
 * <p>Packet ids are written literally, as elsewhere, so the test asserts what 1.20.2
 * requires rather than agreeing with the registry.
 */
class BackendLossFallbackTest {

    private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_20_2;

    private static final int SB_PLAY_CONFIG_ACK = 0x0B;
    private static final int CB_PLAY_START_CONFIGURATION = 0x65;
    private static final int CB_PLAY_SYSTEM_CHAT = 0x67;
    private static final int CB_PLAY_DISCONNECT = 0x1B;
    private static final int CB_CONFIG_REGISTRY = 0x05;
    private static final int CB_CONFIG_FINISH = 0x02;
    private static final int SB_CONFIG_FINISH_ACK = 0x02;
    private static final int CB_LOGIN_SUCCESS = 0x02;
    private static final int SB_LOGIN_ACK = 0x03;

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
    void aPlayerWhoseBackendDiesIsMovedToAnotherServer(@TempDir Path dir) throws Exception {
        Backend lobby = new Backend("lobby", LOBBY_MARKER, true);
        Backend survival = new Backend("survival", SURVIVAL_MARKER, false);

        CompletableFuture<Void> lobbyReady = lobby.serve();
        CompletableFuture<Void> survivalReady = survival.serve();

        int port = freePort();
        writeConfig(dir, port, lobby.port(), survival.port(), "\"lobby\", \"survival\"");

        proxy = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        proxy.start();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            login(out, in, port);
            lobbyReady.get(15, TimeUnit.SECONDS);

            // Playing on lobby. The backend hangs up immediately afterwards.
            ByteBuf world = readUntilId(in, LOBBY_MARKER);
            assertNotNull(world, "expected play data from lobby");
            world.release();

            // The player is told before anything else, because the move itself takes them
            // out of play state and chat sent after that has nowhere to render.
            ByteBuf notice = readUntilId(in, CB_PLAY_SYSTEM_CHAT);
            assertNotNull(notice, "the player was never told their server had gone");
            assertTrue(text(notice).contains("lobby"),
                    "the notice should name the server that was lost");

            // From here it is an ordinary switch: leave play, be configured, re-enter.
            ByteBuf startConfiguration = readUntilId(in, CB_PLAY_START_CONFIGURATION);
            assertNotNull(startConfiguration, "Relay never moved the player off the dead backend");
            startConfiguration.release();

            writeFrame(out, Unpooled.buffer().writeByte(SB_PLAY_CONFIG_ACK));
            out.flush();

            ByteBuf registry = readUntilId(in, CB_CONFIG_REGISTRY);
            assertNotNull(registry, "the new backend's registry data never reached the player");
            assertArrayEquals(payload("survival"), drain(registry),
                    "the new backend's registry data was corrupted in relay");

            ByteBuf finish = readUntilId(in, CB_CONFIG_FINISH);
            assertNotNull(finish, "the new backend never finished configuring the player");
            finish.release();

            writeFrame(out, Unpooled.buffer().writeByte(SB_CONFIG_FINISH_ACK));
            out.flush();

            survivalReady.get(15, TimeUnit.SECONDS);

            ByteBuf survivalWorld = readUntilId(in, SURVIVAL_MARKER);
            assertNotNull(survivalWorld, "no play data arrived from the new backend after the rescue");
            assertArrayEquals(payload("survival"), drain(survivalWorld),
                    "the new backend's play data was corrupted in relay");

            assertEquals(1, survival.playersSeen(), "the new backend should have been logged into once");
        }
    }

    /**
     * With nowhere left to send them, the player is disconnected with the real reason.
     *
     * <p>The dead backend is excluded from its own rescue &mdash; it has just proved it
     * cannot hold a session &mdash; so a one-server network has no candidates at all.
     * That must end the session cleanly rather than leave the client frozen in a world
     * nothing is driving.
     */
    @Test
    void aPlayerIsDisconnectedWhenNothingElseWillTakeThem(@TempDir Path dir) throws Exception {
        Backend lobby = new Backend("lobby", LOBBY_MARKER, true);
        CompletableFuture<Void> lobbyReady = lobby.serve();

        int port = freePort();
        writeConfig(dir, port, lobby.port(), lobby.port(), "\"lobby\"");

        proxy = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        proxy.start();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            login(out, in, port);
            lobbyReady.get(15, TimeUnit.SECONDS);

            ByteBuf world = readUntilId(in, LOBBY_MARKER);
            assertNotNull(world, "expected play data from lobby");
            world.release();

            ByteBuf disconnect = readUntilId(in, CB_PLAY_DISCONNECT);
            assertNotNull(disconnect, "the player was neither moved nor disconnected");
            assertTrue(text(disconnect).contains("lobby"),
                    "the kick should name the backend that was lost");
        }
    }

    /**
     * A backend that kicks on the way down moves the player rather than losing them.
     *
     * <p>This is the case that actually happens. A planned restart kicks everyone before
     * it closes, and a kick relayed as-is takes the client back to the multiplayer menu
     * before Relay can move it &mdash; leaving the close, moments later, with nobody to
     * rescue. Covering only the crash case would look like a working feature and fail on
     * every ordinary restart.
     */
    @Test
    void aBackendKickMovesThePlayerInsteadOfEndingTheirSession(@TempDir Path dir) throws Exception {
        Backend lobby = new Backend("lobby", LOBBY_MARKER, true, "{\"text\":\"Server closed\"}");
        Backend survival = new Backend("survival", SURVIVAL_MARKER, false);

        CompletableFuture<Void> lobbyReady = lobby.serve();
        CompletableFuture<Void> survivalReady = survival.serve();

        int port = freePort();
        writeConfig(dir, port, lobby.port(), survival.port(), "\"lobby\", \"survival\"");

        proxy = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        proxy.start();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            login(out, in, port);
            lobbyReady.get(15, TimeUnit.SECONDS);

            ByteBuf world = readUntilId(in, LOBBY_MARKER);
            assertNotNull(world, "expected play data from lobby");
            world.release();

            // The kick is claimed, and its wording carried into the notice: a player
            // kicked for a reason should still be told the reason.
            ByteBuf notice = readUntilId(in, CB_PLAY_SYSTEM_CHAT);
            assertNotNull(notice, "the player was never told why they were moved");
            String text = text(notice);
            assertTrue(text.contains("Server closed"),
                    "the backend's own reason should survive into the notice, got: " + text);

            ByteBuf startConfiguration = readUntilId(in, CB_PLAY_START_CONFIGURATION);
            assertNotNull(startConfiguration, "a kicked player should have been moved, not dropped");
            startConfiguration.release();

            writeFrame(out, Unpooled.buffer().writeByte(SB_PLAY_CONFIG_ACK));
            out.flush();

            ByteBuf registry = readUntilId(in, CB_CONFIG_REGISTRY);
            assertNotNull(registry, "the new backend never configured the kicked player");
            registry.release();

            ByteBuf finish = readUntilId(in, CB_CONFIG_FINISH);
            assertNotNull(finish, "the new backend never finished configuring the player");
            finish.release();

            writeFrame(out, Unpooled.buffer().writeByte(SB_CONFIG_FINISH_ACK));
            out.flush();

            survivalReady.get(15, TimeUnit.SECONDS);
            assertEquals(1, survival.playersSeen(), "the kicked player should have landed on the other server");
        }
    }

    /**
     * A kick that cannot be escaped reaches the player in the backend's own words.
     *
     * <p>Claiming the kick must not cost the reason. Someone kicked for being banned and
     * with nowhere else to go should read the ban message, not a summary Relay invented
     * &mdash; especially since from 1.20.3 the reason is network NBT that Relay can write
     * but not read, so the only faithful thing to send is the original bytes.
     */
    @Test
    void anInescapableKickReachesThePlayerUnchanged(@TempDir Path dir) throws Exception {
        Backend lobby = new Backend("lobby", LOBBY_MARKER, true, "{\"text\":\"You are banned\"}");
        CompletableFuture<Void> lobbyReady = lobby.serve();

        int port = freePort();
        writeConfig(dir, port, lobby.port(), lobby.port(), "\"lobby\"");

        proxy = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        proxy.start();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            login(out, in, port);
            lobbyReady.get(15, TimeUnit.SECONDS);

            ByteBuf world = readUntilId(in, LOBBY_MARKER);
            assertNotNull(world, "expected play data from lobby");
            world.release();

            ByteBuf disconnect = readUntilId(in, CB_PLAY_DISCONNECT);
            assertNotNull(disconnect, "the player was never disconnected");
            String text = text(disconnect);
            assertTrue(text.contains("You are banned"),
                    "the backend's reason should have been passed through, got: " + text);
        }
    }

    /**
     * A group survives losing a member: the player lands on a sibling.
     *
     * <p>The reason to run several servers under one name is that any of them can go
     * without the name going with it. That only holds if the try order is resolved to
     * members and the dead one excluded individually — excluding the whole group, which
     * is the easy mistake, would send a player who lost {@code survival-01} past
     * {@code survival-02} entirely and out to whatever came next.
     */
    @Test
    void aGroupFallsBackToItsOtherMember(@TempDir Path dir) throws Exception {
        Backend first = new Backend("survival-01", LOBBY_MARKER, true);
        Backend second = new Backend("survival-02", SURVIVAL_MARKER, false);

        CompletableFuture<Void> firstReady = first.serve();
        CompletableFuture<Void> secondReady = second.serve();

        int port = freePort();
        Files.writeString(dir.resolve("relay.toml"), """
                bind = "127.0.0.1:%d"
                motd = "test"
                online-mode = false
                forwarding-mode = "none"
                health.enabled = false
                control.enabled = false
                compression-threshold = -1
                balance = "first-available"
                try = ["survival"]

                [servers]
                survival-01 = "127.0.0.1:%d"
                survival-02 = "127.0.0.1:%d"

                [groups]
                survival = ["survival-01", "survival-02"]
                """.formatted(port, first.port(), second.port()));

        proxy = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        proxy.start();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // "try" names only the group, so arriving anywhere at all proves a group is
            // a destination in its own right.
            login(out, in, port);
            firstReady.get(15, TimeUnit.SECONDS);

            ByteBuf world = readUntilId(in, LOBBY_MARKER);
            assertNotNull(world, "a group named in the try order should have taken the player");
            world.release();

            ByteBuf startConfiguration = readUntilId(in, CB_PLAY_START_CONFIGURATION);
            assertNotNull(startConfiguration, "losing one member should have moved the player, not dropped them");
            startConfiguration.release();

            writeFrame(out, Unpooled.buffer().writeByte(SB_PLAY_CONFIG_ACK));
            out.flush();

            ByteBuf registry = readUntilId(in, CB_CONFIG_REGISTRY);
            assertNotNull(registry, "the sibling never configured the player");
            registry.release();

            ByteBuf finish = readUntilId(in, CB_CONFIG_FINISH);
            assertNotNull(finish, "the sibling never finished configuring the player");
            finish.release();

            writeFrame(out, Unpooled.buffer().writeByte(SB_CONFIG_FINISH_ACK));
            out.flush();

            secondReady.get(15, TimeUnit.SECONDS);
            assertEquals(1, second.playersSeen(),
                    "the other member of the group should have taken the player over");
        }
    }

    private static void writeConfig(Path dir, int port, int lobbyPort, int survivalPort, String tryOrder)
            throws IOException {
        Files.writeString(dir.resolve("relay.toml"), """
                bind = "127.0.0.1:%d"
                motd = "test"
                online-mode = false
                forwarding-mode = "none"
                health.enabled = false
                control.enabled = false
                compression-threshold = -1
                try = [%s]

                [servers]
                lobby = "127.0.0.1:%d"
                survival = "127.0.0.1:%d"
                """.formatted(port, tryOrder, lobbyPort, survivalPort));
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
        assertNotNull(registry, "the first backend's registry data never arrived");
        registry.release();

        ByteBuf finish = readUntilId(in, CB_CONFIG_FINISH);
        assertNotNull(finish, "the first backend never finished configuring the player");
        finish.release();

        writeFrame(out, Unpooled.buffer().writeByte(SB_CONFIG_FINISH_ACK));
        out.flush();
    }

    /** The readable part of a component payload, whether it arrived as JSON or as NBT. */
    private static String text(ByteBuf body) {
        return new String(drain(body), StandardCharsets.UTF_8);
    }

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

    /** A backend that reaches play state and then, optionally, drops dead. */
    private final class Backend {

        private final String name;
        private final int marker;
        private final boolean dieAfterPlay;
        private final String kickReason;
        private final ServerSocket socket;
        private volatile int playersSeen;

        Backend(String name, int marker, boolean dieAfterPlay) throws IOException {
            this(name, marker, dieAfterPlay, null);
        }

        Backend(String name, int marker, boolean dieAfterPlay, String kickReason) throws IOException {
            this.name = name;
            this.marker = marker;
            this.dieAfterPlay = dieAfterPlay;
            this.kickReason = kickReason;
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

                    if (dieAfterPlay) {
                        if (kickReason != null) {
                            // What a planned shutdown looks like: everyone is kicked
                            // first, and the socket closes afterwards.
                            ByteBuf kick = Unpooled.buffer();
                            kick.writeByte(CB_PLAY_DISCONNECT);
                            ProtocolUtils.writeString(kick, kickReason);
                            writeFrame(out, kick);
                            out.flush();
                        }
                        // Half-close rather than close outright. The proxy has already
                        // written to this backend -- the plugin-channel registration, at
                        // least -- and closing a socket with data still unread in its
                        // receive buffer makes TCP send a reset, which throws away the
                        // marker written just above. The player would then never have
                        // provably reached play state, which is the whole premise here.
                        connection.shutdownOutput();
                    }
                    while (in.read() != -1) {
                        // Held open until the proxy lets go.
                    }
                } catch (Exception e) {
                    ready.completeExceptionally(e);
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
