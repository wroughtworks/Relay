package dev.relay.proxy;

import dev.relay.auth.GameProfile;
import dev.relay.config.ConfigLoader;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Watches what Relay sends to a backend, using a plain {@link ServerSocket} standing in
 * for Paper.
 *
 * <p>{@code ProxyLoginTest} points at a dead port, so it never gets far enough to see the
 * outbound handshake. That gap hid a real bug: the connection was being moved to login
 * state before the handshake was written, and the handshake packet only has an id in
 * handshake state. Anything that inspects the backend side belongs here.
 */
class BackendHandshakeTest {

    private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;

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
    void sendsAHandshakeThenALoginStartToTheBackend(@TempDir Path dir) throws Exception {
        Backend observed = runLogin(dir, "none");

        ByteBuf handshake = observed.frames().get(0);
        assertEquals(0x00, ProtocolUtils.readVarInt(handshake), "expected a handshake packet");
        assertEquals(VERSION.id(), ProtocolUtils.readVarInt(handshake),
                "the backend must be told the player's protocol version, not the proxy's");
        assertEquals("127.0.0.1", ProtocolUtils.readString(handshake));
        assertEquals(observed.port(), handshake.readUnsignedShort());
        assertEquals(2, ProtocolUtils.readVarInt(handshake), "next state should be login");

        ByteBuf loginStart = observed.frames().get(1);
        assertEquals(0x00, ProtocolUtils.readVarInt(loginStart), "expected a login start packet");
        assertEquals("Tester", ProtocolUtils.readString(loginStart));
        assertEquals(GameProfile.offline("Tester").uuid(), ProtocolUtils.readUuid(loginStart),
                "the backend must receive the profile Relay authenticated, not the client's claim");
    }

    /**
     * Legacy forwarding smuggles the profile into the handshake hostname, NUL separated:
     * {@code host\0ip\0undashedUuid\0propertiesJson}.
     */
    @Test
    void legacyForwardingAppendsProfileDataToTheHostname(@TempDir Path dir) throws Exception {
        Backend observed = runLogin(dir, "legacy");

        ByteBuf handshake = observed.frames().get(0);
        ProtocolUtils.readVarInt(handshake); // packet id
        ProtocolUtils.readVarInt(handshake); // protocol version
        String hostname = ProtocolUtils.readString(handshake);

        String[] parts = hostname.split("\0");
        assertEquals(4, parts.length, "expected host, ip, uuid and properties in '" + hostname + "'");
        assertEquals("127.0.0.1", parts[0]);
        assertEquals("127.0.0.1", parts[1], "the player's address, not the proxy's");
        assertEquals(GameProfile.offline("Tester").uuid().toString().replace("-", ""), parts[2],
                "the UUID must be undashed");
        assertEquals("[]", parts[3], "an offline profile has no properties");
    }

    /**
     * With {@code proxy-protocol-send}, a PROXY header must precede the handshake and sit
     * outside Minecraft's length framing.
     *
     * <p>Read here with Netty's own decoder in front of the frame reader, mirroring how a
     * Paper backend running {@code proxies.proxy-protocol: true} consumes it. Getting the
     * order or the framing wrong produces a backend that reads nothing and answers
     * nothing.
     */
    @Test
    void sendsAProxyProtocolHeaderAheadOfTheHandshake(@TempDir Path dir) throws Exception {
        Backend observed = runLogin(dir, "none", true);

        HAProxyMessage header = observed.proxyHeader();
        assertNotNull(header, "no PROXY header was sent");
        try {
            assertEquals(HAProxyCommand.PROXY, header.command());
            assertEquals("127.0.0.1", header.sourceAddress(), "the backend must see the player's address");
            assertEquals(observed.port(), header.destinationPort());
        } finally {
            header.release();
        }

        // The handshake must still follow, intact and correctly framed.
        ByteBuf handshake = observed.frames().get(0);
        assertEquals(0x00, ProtocolUtils.readVarInt(handshake));
        assertEquals(VERSION.id(), ProtocolUtils.readVarInt(handshake));
    }

    /** Without the setting, nothing extra is prepended. */
    @Test
    void sendsNoProxyProtocolHeaderByDefault(@TempDir Path dir) throws Exception {
        Backend observed = runLogin(dir, "none", false);
        assertNull(observed.proxyHeader(), "a PROXY header was sent without being configured");
    }

    // ------------------------------------------------------------ harness

    /** Everything the fake backend saw, plus the port it was listening on. */
    private record Backend(int port, List<ByteBuf> frames, HAProxyMessage proxyHeader) {
    }

    private Backend runLogin(Path dir, String forwardingMode) throws Exception {
        return runLogin(dir, forwardingMode, false);
    }

    /**
     * Runs a complete offline login against a fake backend and returns what it received:
     * the PROXY header if one was sent, then the first two Minecraft frames.
     */
    private Backend runLogin(Path dir, String forwardingMode, boolean expectProxyHeader) throws Exception {
        backend = new ServerSocket();
        backend.bind(new InetSocketAddress("127.0.0.1", 0));
        int backendPort = backend.getLocalPort();

        // Accept off-thread: Relay only dials the backend once the client has
        // acknowledged login, which has not happened yet.
        CompletableFuture<Backend> received = new CompletableFuture<>();
        Thread acceptor = new Thread(() -> {
            try (Socket connection = backend.accept()) {
                connection.setSoTimeout(10_000);
                InputStream in = connection.getInputStream();
                HAProxyMessage header = expectProxyHeader ? readProxyHeader(in) : null;
                received.complete(new Backend(backendPort, List.of(readFrame(in), readFrame(in)), header));
            } catch (Exception e) {
                received.completeExceptionally(e);
            }
        }, "fake-backend");
        acceptor.setDaemon(true);
        acceptor.start();

        int port = freePort();
        proxy = start(dir, port, backendPort, forwardingMode, expectProxyHeader);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();

            writeFrame(out, handshake(VERSION.id(), "relay.test", port, 2));

            ByteBuf loginStart = Unpooled.buffer();
            loginStart.writeByte(0x00);
            ProtocolUtils.writeString(loginStart, "Tester");
            ProtocolUtils.writeUuid(loginStart, UUID.randomUUID());
            writeFrame(out, loginStart);
            out.flush();

            readFrame(socket.getInputStream()).release(); // login success

            writeFrame(out, Unpooled.buffer().writeByte(0x03)); // login acknowledged
            out.flush();

            return received.get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Reads a v2 PROXY header the way a backend does: a fixed 16-byte prologue whose last
     * two bytes give the length of the address block that follows.
     */
    private static HAProxyMessage readProxyHeader(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        byte[] prologue = new byte[16];
        data.readFully(prologue);
        int addressLength = ((prologue[14] & 0xFF) << 8) | (prologue[15] & 0xFF);
        byte[] addresses = new byte[addressLength];
        data.readFully(addresses);

        EmbeddedChannel decoder = new EmbeddedChannel(new HAProxyMessageDecoder());
        try {
            decoder.writeInbound(Unpooled.wrappedBuffer(prologue, addresses));
            return decoder.readInbound();
        } finally {
            decoder.finishAndReleaseAll();
        }
    }

    private static RelayProxy start(Path dir, int port, int backendPort, String forwardingMode,
                                    boolean proxyProtocolSend) throws Exception {
        Path config = dir.resolve("relay.toml");
        Files.writeString(config, """
                bind = "127.0.0.1:%d"
                motd = "test"
                online-mode = false
                forwarding-mode = "%s"
                health.enabled = false
                compression-threshold = -1
                proxy-protocol-send = %s

                [servers]
                lobby = "127.0.0.1:%d"
                """.formatted(port, forwardingMode, proxyProtocolSend, backendPort));

        RelayProxy proxy = new RelayProxy(ConfigLoader.load(config));
        proxy.start();
        return proxy;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private static ByteBuf handshake(int protocol, String host, int port, int nextState) {
        ByteBuf buf = Unpooled.buffer();
        buf.writeByte(0x00);
        ProtocolUtils.writeVarInt(buf, protocol);
        ProtocolUtils.writeString(buf, host);
        buf.writeShort(port);
        ProtocolUtils.writeVarInt(buf, nextState);
        return buf;
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
