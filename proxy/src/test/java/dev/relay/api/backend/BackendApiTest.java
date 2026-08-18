package dev.relay.api.backend;

import dev.relay.config.ConfigLoader;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import dev.relay.health.BackendStats;
import dev.relay.proxy.RelayProxy;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backend-facing plugin-message API, driven from a fake backend over a real socket.
 *
 * <p>Requests are built exactly as a Bukkit plugin builds them &mdash; a
 * {@code DataOutputStream} on the {@code bungeecord:main} channel &mdash; so the test
 * doubles as a check that Relay is wire-compatible with BungeeCord rather than merely
 * self-consistent.
 */
class BackendApiTest {

    private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_20_2;

    /** 1.20.2 play ids. Clientbound is what a backend sends; serverbound is the reply. */
    private static final int CB_PLAY_PLUGIN_MESSAGE = 0x18;
    private static final int SB_PLAY_PLUGIN_MESSAGE = 0x0F;
    private static final int CB_CONFIG_FINISH = 0x02;
    private static final int SB_CONFIG_FINISH_ACK = 0x02;
    private static final int CB_LOGIN_SUCCESS = 0x02;
    private static final int SB_LOGIN_ACK = 0x03;

    private RelayProxy proxy;
    private ServerSocket backend;
    private Socket backendConnection;

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
     * Relay must announce the API channels to the backend before anything else works.
     *
     * <p>Bukkit drops a plugin message whose channel the receiving client has not
     * registered, and Relay is the client from a backend's point of view. Without this
     * announcement a plugin's requests never leave the server, which looks exactly like
     * the proxy ignoring them.
     */
    @Test
    void theApiChannelsAreRegisteredWithTheBackend(@TempDir Path dir) throws Exception {
        join(dir);

        InputStream in = backendConnection.getInputStream();
        for (int i = 0; i < 50; i++) {
            ByteBuf frame = readFrame(in);
            try {
                if (ProtocolUtils.readVarInt(frame) != SB_PLAY_PLUGIN_MESSAGE) {
                    continue;
                }
                if (!ProtocolUtils.readString(frame, 256).equals("minecraft:register")) {
                    continue;
                }
                String announced = new String(ProtocolUtils.readRemaining(frame),
                        java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(announced.contains(BackendApi.BUNGEE_CHANNEL),
                        "expected the BungeeCord channel, got: " + announced.replace('\0', ' '));
                return;
            } catch (RuntimeException ignored) {
                // Not a plugin message; keep looking.
            } finally {
                frame.release();
            }
        }
        org.junit.jupiter.api.Assertions.fail("Relay never registered its channels with the backend");
    }

    /**
     * A command forwarded from a backend runs against the proxy's own permission nodes,
     * so routing it this way grants nothing the player could not do by typing it.
     */
    @Test
    void runCommandIsRefusedWithoutThePermission(@TempDir Path dir) throws Exception {
        Client client = join(dir, "");
        try {
            sendApiRequest(BackendApi.RELAY_CHANNEL, out -> {
                out.writeUTF("RunCommand");
                out.writeUTF("glist");
            });
            // With no permission the proxy declines, and says so on the player's
            // connection rather than replying on the API channel.
            assertTrue(awaitPlayerMessage(client), "the player should have been told");
        } finally {
            client.socket().close();
        }
    }

    @Test
    void getServerNamesTheBackend(@TempDir Path dir) throws Exception {
        DataInputStream reply = exchange(dir, out -> out.writeUTF("GetServer"));
        assertEquals("GetServer", reply.readUTF());
        assertEquals("lobby", reply.readUTF());
    }

    @Test
    void getServersListsEveryConfiguredBackend(@TempDir Path dir) throws Exception {
        DataInputStream reply = exchange(dir, out -> out.writeUTF("GetServers"));
        assertEquals("GetServers", reply.readUTF());
        String names = reply.readUTF();
        assertTrue(names.contains("lobby"), names);
        assertTrue(names.contains("survival"), names);
    }

    @Test
    void playerCountReportsTheOnlinePlayer(@TempDir Path dir) throws Exception {
        DataInputStream reply = exchange(dir, out -> {
            out.writeUTF("PlayerCount");
            out.writeUTF("ALL");
        });
        assertEquals("PlayerCount", reply.readUTF());
        assertEquals("ALL", reply.readUTF());
        assertEquals(1, reply.readInt());
    }

    @Test
    void playerListNamesTheOnlinePlayer(@TempDir Path dir) throws Exception {
        DataInputStream reply = exchange(dir, out -> {
            out.writeUTF("PlayerList");
            out.writeUTF("ALL");
        });
        assertEquals("PlayerList", reply.readUTF());
        assertEquals("ALL", reply.readUTF());
        assertEquals("Tester", reply.readUTF());
    }

    @Test
    void ipReportsThePlayersRealAddress(@TempDir Path dir) throws Exception {
        DataInputStream reply = exchange(dir, out -> out.writeUTF("IP"));
        assertEquals("IP", reply.readUTF());
        assertEquals("127.0.0.1", reply.readUTF());
        assertTrue(reply.readInt() > 0, "a real source port should be reported");
    }

    /** The UUID is sent undashed, as BungeeCord sends it. */
    @Test
    void uuidIsReportedUndashed(@TempDir Path dir) throws Exception {
        DataInputStream reply = exchange(dir, out -> out.writeUTF("UUID"));
        assertEquals("UUID", reply.readUTF());
        String uuid = reply.readUTF();
        assertEquals(32, uuid.length(), "expected an undashed UUID, got " + uuid);
        assertTrue(!uuid.contains("-"));
    }

    /**
     * Traffic on the API channel must be consumed, never relayed onward.
     *
     * <p>A client has no business seeing proxy control traffic, and a plugin that assumed
     * otherwise would be leaking server internals to every player.
     */
    @Test
    void apiMessagesAreNotForwardedToThePlayer(@TempDir Path dir) throws Exception {
        Client client = join(dir);
        try {
            sendApiRequest(out -> out.writeUTF("GetServer"));
            readApiReply();

            // A marked play packet after the API exchange: if the API message had been
            // relayed, it would arrive first.
            ByteBuf marker = Unpooled.buffer();
            marker.writeByte(0x26);
            marker.writeBytes(new byte[]{9, 9, 9});
            writeFrame(backendConnection.getOutputStream(), marker);
            backendConnection.getOutputStream().flush();

            ByteBuf received = readFrame(client.socket().getInputStream());
            try {
                assertEquals(0x26, ProtocolUtils.readVarInt(received),
                        "the API message leaked through to the player");
            } finally {
                received.release();
            }
        } finally {
            client.socket().close();
        }
    }

    /** An unparseable payload must not take the connection down with it. */
    @Test
    void malformedRequestsAreSurvivable(@TempDir Path dir) throws Exception {
        Client client = join(dir);
        try {
            // A truncated UTF string: the length prefix promises far more than follows.
            ByteBuf packet = Unpooled.buffer();
            ProtocolUtils.writeVarInt(packet, CB_PLAY_PLUGIN_MESSAGE);
            ProtocolUtils.writeString(packet, BackendApi.BUNGEE_CHANNEL);
            packet.writeShort(500);
            packet.writeBytes(new byte[]{1, 2, 3});
            writeFrame(backendConnection.getOutputStream(), packet);
            backendConnection.getOutputStream().flush();

            // Still usable afterwards.
            sendApiRequest(out -> out.writeUTF("GetServer"));
            DataInputStream reply = readApiReply();
            assertEquals("GetServer", reply.readUTF());
        } finally {
            client.socket().close();
        }
    }

    // ------------------------------------------------------------ harness

    private record Client(Socket socket) {
    }

    /** Joins a player, sends one API request from the backend, and returns the reply. */
    /**
     * A backend's self-reported load reaches the proxy and is attributed to it.
     *
     * <p>This is the one API message the proxy never asks for, and the only one whose
     * sender is a plugin that may be a version behind. The field order is the contract,
     * so it is worth a test that reads it end to end rather than trusting both sides to
     * have been written from the same list.
     */
    @Test
    void serverStatsAreRecordedAgainstTheReportingBackend(@TempDir Path dir) throws Exception {
        Client client = join(dir);
        try {
            sendApiRequest(BackendApi.RELAY_CHANNEL, out -> {
                out.writeUTF("ServerStats");
                out.writeDouble(19.8);
                out.writeDouble(19.9);
                out.writeDouble(20.0);
                out.writeDouble(31.5);
                out.writeLong(512L * 1024 * 1024);
                out.writeLong(2048L * 1024 * 1024);
                out.writeDouble(0.42);
                out.writeInt(3);
                out.writeLong(7200);
                out.writeUTF("Paper 1.20.2");
            });

            BackendStats stats = awaitStats("lobby");
            assertEquals(19.8, stats.tps1m(), 0.0001);
            assertEquals(31.5, stats.msptMean(), 0.0001);
            assertEquals(0.42, stats.cpuLoad(), 0.0001);
            assertEquals(3, stats.players());
            assertEquals("Paper 1.20.2", stats.version());
            assertTrue(stats.isFresh(), "a report that just arrived is not stale");

            // Attributed to the connection it came in on, never to a name in the payload:
            // a backend must not be able to file figures under another server.
            assertNull(proxy.server("survival").orElseThrow().stats(),
                    "only the reporting backend should have stats");
        } finally {
            client.socket().close();
        }
    }

    private BackendStats awaitStats(String server) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            BackendStats stats = proxy.server(server).orElseThrow().stats();
            if (stats != null) {
                return stats;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(server + " never recorded any stats");
    }

    private DataInputStream exchange(Path dir, Request request) throws Exception {
        Client client = join(dir);
        try {
            sendApiRequest(request);
            return readApiReply();
        } finally {
            client.socket().close();
        }
    }

    private void sendApiRequest(Request request) throws IOException {
        sendApiRequest(BackendApi.BUNGEE_CHANNEL, request);
    }

    /** True once any frame reaches the player, which is how a refusal is delivered. */
    private boolean awaitPlayerMessage(Client client) {
        try {
            client.socket().setSoTimeout(5000);
            readFrame(client.socket().getInputStream()).release();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void sendApiRequest(String channel, Request request) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(body)) {
            request.write(data);
        }

        ByteBuf packet = Unpooled.buffer();
        ProtocolUtils.writeVarInt(packet, CB_PLAY_PLUGIN_MESSAGE);
        ProtocolUtils.writeString(packet, channel);
        packet.writeBytes(body.toByteArray());
        writeFrame(backendConnection.getOutputStream(), packet);
        backendConnection.getOutputStream().flush();
    }

    /** Reads until the proxy answers on the API channel, skipping anything else. */
    private DataInputStream readApiReply() throws IOException {
        InputStream in = backendConnection.getInputStream();
        for (int i = 0; i < 50; i++) {
            ByteBuf frame = readFrame(in);
            try {
                if (ProtocolUtils.readVarInt(frame) != SB_PLAY_PLUGIN_MESSAGE) {
                    continue;
                }
                String channel = ProtocolUtils.readString(frame, 256);
                if (!BackendApi.isApiChannel(channel)) {
                    continue;
                }
                return new DataInputStream(new ByteArrayInputStream(ProtocolUtils.readRemaining(frame)));
            } catch (RuntimeException ignored) {
                // Not a plugin message; keep looking.
            } finally {
                frame.release();
            }
        }
        throw new IOException("the proxy never replied on the API channel");
    }

    /** Starts the proxy and a fake backend, and takes a player through to play state. */
    private Client join(Path dir) throws Exception {
        return join(dir, "relay.command.glist");
    }

    private Client join(Path dir, String permission) throws Exception {
        backend = new ServerSocket();
        backend.bind(new InetSocketAddress("127.0.0.1", 0));

        CompletableFuture<Socket> accepted = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                Socket connection = backend.accept();
                connection.setSoTimeout(20_000);
                InputStream in = connection.getInputStream();
                OutputStream out = connection.getOutputStream();

                readFrame(in).release();                       // handshake
                ByteBuf loginStart = readFrame(in);
                ProtocolUtils.readVarInt(loginStart);
                String username = ProtocolUtils.readString(loginStart);
                UUID uuid = ProtocolUtils.readUuid(loginStart);
                loginStart.release();

                ByteBuf success = Unpooled.buffer();
                success.writeByte(CB_LOGIN_SUCCESS);
                ProtocolUtils.writeUuid(success, uuid);
                ProtocolUtils.writeString(success, username);
                ProtocolUtils.writeVarInt(success, 0);
                writeFrame(out, success);
                out.flush();

                readFrame(in).release();                       // login acknowledged
                writeFrame(out, Unpooled.buffer().writeByte(CB_CONFIG_FINISH));
                out.flush();
                readFrame(in).release();                       // finish configuration ack

                accepted.complete(connection);
            } catch (Exception e) {
                accepted.completeExceptionally(e);
            }
        }, "fake-backend");
        thread.setDaemon(true);
        thread.start();

        int port = freePort();
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
                survival = "127.0.0.1:1"

                [permissions]
                default = [%s]
                """.formatted(port, backend.getLocalPort(),
                permission.isEmpty() ? "" : "\"" + permission + "\""));

        proxy = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        proxy.start();

        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(20_000);
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

        readFrame(in).release();                                   // login success
        writeFrame(out, Unpooled.buffer().writeByte(SB_LOGIN_ACK));
        out.flush();
        readFrame(in).release();                                   // finish configuration
        writeFrame(out, Unpooled.buffer().writeByte(SB_CONFIG_FINISH_ACK));
        out.flush();

        backendConnection = accepted.get(15, TimeUnit.SECONDS);
        assertNotNull(backendConnection);
        return new Client(socket);
    }

    @FunctionalInterface
    private interface Request {
        void write(DataOutputStream out) throws IOException;
    }

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
