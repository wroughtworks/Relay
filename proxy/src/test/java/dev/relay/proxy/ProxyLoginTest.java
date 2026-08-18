package dev.relay.proxy;

import dev.relay.auth.GameProfile;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a full offline-mode login over a real socket, hand-writing the client side.
 *
 * <p>This is the closest thing to a real client the suite has. It covers the sequence a
 * unit test cannot: handshake, login start, the profile Relay synthesises, the
 * acknowledgement, and what the player is told when no backend will take them.
 */
class ProxyLoginTest {

    private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_21_4;

    private RelayProxy proxy;

    @AfterEach
    void stop() {
        if (proxy != null) {
            proxy.shutdown();
        }
    }

    @Test
    void completesAnOfflineModeLoginAndReportsAnUnreachableBackend(@TempDir Path dir) throws Exception {
        // A port nothing is listening on, so the first hop is guaranteed to fail.
        int deadBackend = freePort();
        int port = freePort();
        proxy = start(dir, port, deadBackend);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            writeFrame(out, handshake(VERSION.id(), "relay.test", port, 2));

            ByteBuf loginStart = Unpooled.buffer();
            loginStart.writeByte(0x00);
            ProtocolUtils.writeString(loginStart, "Tester");
            ProtocolUtils.writeUuid(loginStart, UUID.randomUUID());
            writeFrame(out, loginStart);
            out.flush();

            // --- Login Success -------------------------------------------------
            ByteBuf success = readFrame(in);
            try {
                assertEquals(0x02, ProtocolUtils.readVarInt(success), "expected Login Success");
                UUID uuid = ProtocolUtils.readUuid(success);
                String name = ProtocolUtils.readString(success);

                assertEquals("Tester", name);
                assertEquals(GameProfile.offline("Tester").uuid(), uuid,
                        "offline mode must derive the UUID rather than trust the client's");
                assertEquals(0, ProtocolUtils.readVarInt(success), "an offline profile has no properties");
                // 1.21.4 is past the window where strictErrorHandling was present.
                assertTrue(!success.isReadable(), "unexpected trailing bytes in Login Success");
            } finally {
                success.release();
            }

            // --- Acknowledge, moving into configuration state -------------------
            writeFrame(out, Unpooled.buffer().writeByte(0x03));
            out.flush();

            // The only backend is unreachable, so the fallback list runs out and Relay
            // kicks with a configuration-state disconnect.
            ByteBuf disconnect = readFrame(in);
            try {
                assertEquals(0x02, ProtocolUtils.readVarInt(disconnect),
                        "expected a configuration-state disconnect");
                // 1.20.3+ encodes the reason as network NBT: 0x0a is TAG_Compound.
                assertEquals(0x0a, disconnect.getUnsignedByte(disconnect.readerIndex()),
                        "the reason should be an NBT compound at this version");
            } finally {
                disconnect.release();
            }
        }
    }

    @Test
    void refusesAnInvalidUsername(@TempDir Path dir) throws Exception {
        int port = freePort();
        proxy = start(dir, port, freePort());

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();

            writeFrame(out, handshake(VERSION.id(), "relay.test", port, 2));

            ByteBuf loginStart = Unpooled.buffer();
            loginStart.writeByte(0x00);
            ProtocolUtils.writeString(loginStart, "bad name!");
            ProtocolUtils.writeUuid(loginStart, UUID.randomUUID());
            writeFrame(out, loginStart);
            out.flush();

            ByteBuf response = readFrame(socket.getInputStream());
            try {
                assertEquals(0x00, ProtocolUtils.readVarInt(response), "expected a login disconnect");
                assertTrue(ProtocolUtils.readString(response).contains("Invalid username"));
            } finally {
                response.release();
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private static RelayProxy start(Path dir, int port, int backendPort) throws Exception {
        Path config = dir.resolve("relay.toml");
        Files.writeString(config, """
                bind = "127.0.0.1:%d"
                motd = "test"
                online-mode = false
                forwarding-mode = "none"
                health.enabled = false
                compression-threshold = -1

                [servers]
                lobby = "127.0.0.1:%d"
                """.formatted(port, backendPort));

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
