package dev.relay.api;

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

import dev.relay.proxy.RelayProxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the client API over a real socket, as a modded client would.
 *
 * <p>The security-relevant cases matter most here: the client is an attacker-controlled
 * peer, so the tests check what happens when it lies about its permissions, asks for
 * things it was never offered, and floods the channel.
 */
class ClientApiSessionTest {

    private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_20_2;
    /**
     * Serverbound play plugin message id at 1.20.2, taken from the protocol rather than
     * from Relay's own registry &mdash; reading it from {@code StateRegistry} would make
     * this test agree with a wrong id instead of catching it.
     */
    private static final int PLUGIN_MESSAGE_ID = 0x0F;
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
    void helloIsAnsweredWithNegotiatedVersionAndCapabilities(@TempDir Path dir) throws Exception {
        Client client = join(dir, List.of("relay.command.server", "relay.command.glist"));

        client.send(hello(1, "TestMod"));
        ByteBuf welcome = client.readApiMessage();
        try {
            assertEquals(ClientApi.Clientbound.WELCOME, ProtocolUtils.readVarInt(welcome));
            assertEquals(1, ProtocolUtils.readVarInt(welcome), "negotiated protocol version");
            assertTrue(ProtocolUtils.readString(welcome).startsWith("Relay"), "proxy identity");

            List<String> capabilities = readStrings(welcome);
            assertTrue(capabilities.contains(ClientApi.Capability.SERVER_LIST), "expected server_list");
            assertTrue(capabilities.contains(ClientApi.Capability.SWITCH), "expected switch");
        } finally {
            welcome.release();
        }
    }

    /** A newer mod against this proxy must settle on the version the proxy speaks. */
    @Test
    void aNewerClientNegotiatesDownToTheProxyVersion(@TempDir Path dir) throws Exception {
        Client client = join(dir, List.of("relay.command.glist"));

        client.send(hello(99, "FutureMod"));
        ByteBuf welcome = client.readApiMessage();
        try {
            assertEquals(ClientApi.Clientbound.WELCOME, ProtocolUtils.readVarInt(welcome));
            assertEquals(ClientApi.PROTOCOL_VERSION, ProtocolUtils.readVarInt(welcome));
        } finally {
            welcome.release();
        }
    }

    /** Capabilities reflect permissions, so a mod can render only what the player may do. */
    @Test
    void capabilitiesAreFilteredByPermission(@TempDir Path dir) throws Exception {
        Client client = join(dir, List.of("relay.command.glist"));

        client.send(hello(1, "TestMod"));
        ByteBuf welcome = client.readApiMessage();
        try {
            ProtocolUtils.readVarInt(welcome);
            ProtocolUtils.readVarInt(welcome);
            ProtocolUtils.readString(welcome);

            List<String> capabilities = readStrings(welcome);
            assertTrue(capabilities.contains(ClientApi.Capability.SERVER_LIST));
            assertFalse(capabilities.contains(ClientApi.Capability.SWITCH),
                    "switch must not be offered without relay.command.server");
        } finally {
            welcome.release();
        }
    }

    /**
     * The important one. A client that was never offered a capability can still ask for
     * it &mdash; the advertised list is a convenience, not the security boundary.
     */
    @Test
    void requestingAnUnpermittedCapabilityIsRefused(@TempDir Path dir) throws Exception {
        Client client = join(dir, List.of("relay.command.glist"));

        client.send(hello(1, "MaliciousMod"));
        client.readApiMessage().release();

        // Ask to switch anyway, despite switch never appearing in the welcome.
        ByteBuf request = Unpooled.buffer();
        ProtocolUtils.writeVarInt(request, ClientApi.Serverbound.REQUEST_SWITCH);
        ProtocolUtils.writeString(request, "lobby");
        client.send(drain(request));

        ByteBuf response = client.readApiMessage();
        try {
            assertEquals(ClientApi.Clientbound.ERROR, ProtocolUtils.readVarInt(response));
            assertEquals(ClientApi.Error.NO_PERMISSION, ProtocolUtils.readVarInt(response));
        } finally {
            response.release();
        }
    }

    @Test
    void serverListReportsLivePlayerCounts(@TempDir Path dir) throws Exception {
        Client client = join(dir, List.of("relay.command.glist"));

        client.send(hello(1, "TestMod"));
        client.readApiMessage().release();

        ByteBuf request = Unpooled.buffer();
        ProtocolUtils.writeVarInt(request, ClientApi.Serverbound.LIST_SERVERS);
        client.send(drain(request));

        ByteBuf response = client.readApiMessage();
        try {
            assertEquals(ClientApi.Clientbound.SERVER_LIST, ProtocolUtils.readVarInt(response));
            int count = ProtocolUtils.readVarInt(response);
            assertEquals(1, count, "one backend is configured");

            assertEquals("lobby", ProtocolUtils.readString(response));
            assertEquals(1, ProtocolUtils.readVarInt(response), "the joined player should be counted");
            assertTrue(response.readBoolean(), "the player's current server should be flagged");
        } finally {
            response.release();
        }
    }

    /** Requests before the version handshake would be answered in an unagreed format. */
    @Test
    void requestsBeforeHelloAreRefused(@TempDir Path dir) throws Exception {
        Client client = join(dir, List.of("relay.command.glist"));

        ByteBuf request = Unpooled.buffer();
        ProtocolUtils.writeVarInt(request, ClientApi.Serverbound.LIST_SERVERS);
        client.send(drain(request));

        ByteBuf response = client.readApiMessage();
        try {
            assertEquals(ClientApi.Clientbound.ERROR, ProtocolUtils.readVarInt(response));
            assertEquals(ClientApi.Error.MALFORMED, ProtocolUtils.readVarInt(response));
        } finally {
            response.release();
        }
    }

    @Test
    void anUnknownServerIsRefusedRatherThanIgnored(@TempDir Path dir) throws Exception {
        Client client = join(dir, List.of("relay.command.server", "relay.command.glist"));

        client.send(hello(1, "TestMod"));
        client.readApiMessage().release();

        ByteBuf request = Unpooled.buffer();
        ProtocolUtils.writeVarInt(request, ClientApi.Serverbound.REQUEST_SWITCH);
        ProtocolUtils.writeString(request, "does-not-exist");
        client.send(drain(request));

        ByteBuf response = client.readApiMessage();
        try {
            assertEquals(ClientApi.Clientbound.ERROR, ProtocolUtils.readVarInt(response));
            assertEquals(ClientApi.Error.UNKNOWN_SERVER, ProtocolUtils.readVarInt(response));
        } finally {
            response.release();
        }
    }

    /** Garbage on the channel must not kill a session that is otherwise behaving. */
    @Test
    void malformedInputIsRefusedWithoutDroppingTheSession(@TempDir Path dir) throws Exception {
        Client client = join(dir, List.of("relay.command.glist"));

        client.send(hello(1, "TestMod"));
        client.readApiMessage().release();

        // A switch request with a truncated string body.
        ByteBuf malformed = Unpooled.buffer();
        ProtocolUtils.writeVarInt(malformed, ClientApi.Serverbound.REQUEST_SWITCH);
        ProtocolUtils.writeVarInt(malformed, 200); // claims 200 bytes, sends none
        client.send(drain(malformed));

        ByteBuf response = client.readApiMessage();
        try {
            assertEquals(ClientApi.Clientbound.ERROR, ProtocolUtils.readVarInt(response));
            assertEquals(ClientApi.Error.MALFORMED, ProtocolUtils.readVarInt(response));
        } finally {
            response.release();
        }

        // Still usable afterwards.
        ByteBuf request = Unpooled.buffer();
        ProtocolUtils.writeVarInt(request, ClientApi.Serverbound.LIST_SERVERS);
        client.send(drain(request));

        ByteBuf list = client.readApiMessage();
        try {
            assertEquals(ClientApi.Clientbound.SERVER_LIST, ProtocolUtils.readVarInt(list));
        } finally {
            list.release();
        }
    }

    @Test
    void floodingTheChannelIsRateLimited(@TempDir Path dir) throws Exception {
        Client client = join(dir, List.of("relay.command.glist"));

        client.send(hello(1, "TestMod"));
        client.readApiMessage().release();

        int limited = 0;
        for (int i = 0; i < ClientApi.MAX_MESSAGES_PER_SECOND + 10; i++) {
            ByteBuf request = Unpooled.buffer();
            ProtocolUtils.writeVarInt(request, ClientApi.Serverbound.LIST_SERVERS);
            client.send(drain(request));
        }
        for (int i = 0; i < ClientApi.MAX_MESSAGES_PER_SECOND + 10; i++) {
            ByteBuf response = client.readApiMessage();
            try {
                if (ProtocolUtils.readVarInt(response) == ClientApi.Clientbound.ERROR
                        && ProtocolUtils.readVarInt(response) == ClientApi.Error.RATE_LIMITED) {
                    limited++;
                }
            } finally {
                response.release();
            }
        }
        assertTrue(limited > 0, "a flood should have been rate limited");
    }

    // ------------------------------------------------------------ harness

    private static byte[] hello(int version, String modName) {
        ByteBuf buf = Unpooled.buffer();
        ProtocolUtils.writeVarInt(buf, ClientApi.Serverbound.HELLO);
        ProtocolUtils.writeVarInt(buf, version);
        ProtocolUtils.writeString(buf, modName);
        return drain(buf);
    }

    private static List<String> readStrings(ByteBuf buf) {
        int count = ProtocolUtils.readVarInt(buf);
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(ProtocolUtils.readString(buf));
        }
        return values;
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

    private record Client(Socket socket) {
        void send(byte[] apiMessage) throws IOException {
            ByteBuf packet = Unpooled.buffer();
            ProtocolUtils.writeVarInt(packet, PLUGIN_MESSAGE_ID);
            ProtocolUtils.writeString(packet, ClientApi.CHANNEL);
            packet.writeBytes(apiMessage);
            writeFrame(socket.getOutputStream(), packet);
            socket.getOutputStream().flush();
        }

        /** Reads until a plugin message on Relay's channel arrives, skipping game traffic. */
        ByteBuf readApiMessage() throws IOException {
            for (int i = 0; i < 200; i++) {
                ByteBuf frame = readFrame(socket.getInputStream());
                boolean matched = false;
                try {
                    ProtocolUtils.readVarInt(frame); // packet id
                    String channel = ProtocolUtils.readString(frame, 256);
                    if (channel.equals(ClientApi.CHANNEL)) {
                        matched = true;
                        return frame.copy();
                    }
                } catch (RuntimeException ignored) {
                    // Not a plugin message; keep looking.
                } finally {
                    if (!matched) {
                        frame.release();
                    } else {
                        frame.release();
                    }
                }
            }
            throw new IOException("no client API message arrived");
        }
    }

    /** Joins a player and leaves them in play state on a backend that stays silent. */
    private Client join(Path dir, List<String> permissions) throws Exception {
        backend = new ServerSocket();
        backend.bind(new InetSocketAddress("127.0.0.1", 0));

        CompletableFuture<Void> ready = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try (Socket connection = backend.accept()) {
                connection.setSoTimeout(20_000);
                InputStream in = connection.getInputStream();
                OutputStream out = connection.getOutputStream();

                readFrame(in).release();
                ByteBuf loginStart = readFrame(in);
                ProtocolUtils.readVarInt(loginStart);
                String name = ProtocolUtils.readString(loginStart);
                UUID uuid = ProtocolUtils.readUuid(loginStart);
                loginStart.release();

                ByteBuf success = Unpooled.buffer();
                success.writeByte(0x02);
                ProtocolUtils.writeUuid(success, uuid);
                ProtocolUtils.writeString(success, name);
                ProtocolUtils.writeVarInt(success, 0);
                writeFrame(out, success);
                out.flush();

                readFrame(in).release(); // login acknowledged
                writeFrame(out, Unpooled.buffer().writeByte(FINISH_CONFIGURATION));
                out.flush();
                readFrame(in).release(); // finish configuration acknowledged

                ready.complete(null);
                Thread.sleep(20_000);
            } catch (Exception e) {
                ready.completeExceptionally(e);
            }
        }, "fake-backend");
        thread.setDaemon(true);
        thread.start();

        int port = freePort();
        StringBuilder permissionBlock = new StringBuilder("[permissions]\ndefault = [");
        for (int i = 0; i < permissions.size(); i++) {
            permissionBlock.append(i > 0 ? ", " : "").append('"').append(permissions.get(i)).append('"');
        }
        permissionBlock.append("]\n");

        Path config = dir.resolve("relay.toml");
        Files.writeString(config, """
                bind = "127.0.0.1:%d"
                motd = "test"
                online-mode = false
                forwarding-mode = "none"
                health.enabled = false
                control.enabled = false
                compression-threshold = -1
                client-api = true

                [servers]
                lobby = "127.0.0.1:%d"

                %s
                """.formatted(port, backend.getLocalPort(), permissionBlock));

        proxy = new RelayProxy(ConfigLoader.load(config));
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

        readFrame(in).release(); // login success
        writeFrame(out, Unpooled.buffer().writeByte(0x03)); // login acknowledged
        out.flush();
        readFrame(in).release(); // finish configuration
        writeFrame(out, Unpooled.buffer().writeByte(FINISH_CONFIGURATION));
        out.flush();

        ready.join();
        return new Client(socket);
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
