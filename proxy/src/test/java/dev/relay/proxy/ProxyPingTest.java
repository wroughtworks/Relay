package dev.relay.proxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.relay.config.ConfigLoader;
import dev.relay.config.RelayConfig;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a real socket against a real listener.
 *
 * <p>The unit tests verify each codec in isolation; this verifies that the assembled
 * proxy actually answers a client. It speaks the wire protocol by hand rather than
 * reusing Relay's own encoders, so a symmetric bug in encode and decode cannot cancel
 * itself out and pass.
 */
class ProxyPingTest {

    private RelayProxy proxy;

    @AfterEach
    void stop() {
        if (proxy != null) {
            proxy.shutdown();
        }
    }

    @Test
    void answersAServerListPing(@TempDir Path dir) throws Exception {
        int port = freePort();
        proxy = start(dir, port);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();

            writeFrame(out, handshake(ProtocolVersion.MINECRAFT_1_21_4.id(), "relay.test", port, 1));
            writeFrame(out, Unpooled.buffer().writeByte(0x00)); // status request
            out.flush();

            ByteBuf response = readFrame(socket.getInputStream());
            try {
                assertEquals(0x00, ProtocolUtils.readVarInt(response), "expected a status response");
                JsonObject json = JsonParser.parseString(ProtocolUtils.readString(response)).getAsJsonObject();

                assertEquals(64, json.getAsJsonObject("players").get("max").getAsInt());
                assertEquals(0, json.getAsJsonObject("players").get("online").getAsInt());
                assertEquals(ProtocolVersion.MINECRAFT_1_21_4.id(),
                        json.getAsJsonObject("version").get("protocol").getAsInt(),
                        "a supported client should see its own protocol echoed back");
                assertTrue(json.getAsJsonObject("version").get("name").getAsString().startsWith("Relay"));
                assertNotNull(json.get("description"), "the MOTD must be present");
            } finally {
                response.release();
            }
        }
    }

    /** An unsupported client still gets a MOTD, so it can be told what Relay speaks. */
    @Test
    void answersPingsFromUnsupportedVersions(@TempDir Path dir) throws Exception {
        int port = freePort();
        proxy = start(dir, port);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();

            writeFrame(out, handshake(47, "relay.test", port, 1)); // 1.8
            writeFrame(out, Unpooled.buffer().writeByte(0x00));
            out.flush();

            ByteBuf response = readFrame(socket.getInputStream());
            try {
                ProtocolUtils.readVarInt(response);
                JsonObject json = JsonParser.parseString(ProtocolUtils.readString(response)).getAsJsonObject();
                assertEquals(ProtocolVersion.newest().id(),
                        json.getAsJsonObject("version").get("protocol").getAsInt(),
                        "an unsupported client should be shown Relay's newest protocol");
            } finally {
                response.release();
            }
        }
    }

    @Test
    void echoesThePingToken(@TempDir Path dir) throws Exception {
        int port = freePort();
        proxy = start(dir, port);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();

            writeFrame(out, handshake(ProtocolVersion.MINECRAFT_1_21_4.id(), "relay.test", port, 1));
            writeFrame(out, Unpooled.buffer().writeByte(0x00));
            out.flush();
            readFrame(socket.getInputStream()).release();

            long token = 0x0123456789ABCDEFL;
            writeFrame(out, Unpooled.buffer().writeByte(0x01).writeLong(token));
            out.flush();

            ByteBuf pong = readFrame(socket.getInputStream());
            try {
                assertEquals(0x01, ProtocolUtils.readVarInt(pong));
                assertEquals(token, pong.readLong());
            } finally {
                pong.release();
            }
        }
    }

    /** A login from an unsupported version must be refused with a readable reason. */
    @Test
    void refusesUnsupportedVersionsAtLogin(@TempDir Path dir) throws Exception {
        int port = freePort();
        proxy = start(dir, port);

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();

            writeFrame(out, handshake(47, "relay.test", port, 2)); // next state: login
            out.flush();

            ByteBuf response = readFrame(socket.getInputStream());
            try {
                assertEquals(0x00, ProtocolUtils.readVarInt(response), "expected a login disconnect");
                String json = ProtocolUtils.readString(response);
                assertTrue(json.contains("Unsupported client version"), json);
                assertTrue(json.contains(ProtocolVersion.supportedRange()), json);
            } finally {
                response.release();
            }
        }
    }

    // ------------------------------------------------------------ helpers

    private static RelayProxy start(Path dir, int port) throws Exception {
        Path config = dir.resolve("relay.toml");
        Files.writeString(config, """
                bind = "127.0.0.1:%d"
                motd = "<red>Relay test"
                max-players = 64
                online-mode = false
                forwarding-mode = "none"
                health.enabled = false

                [servers]
                lobby = "127.0.0.1:1"
                """.formatted(port));

        RelayConfig loaded = ConfigLoader.load(config);
        RelayProxy proxy = new RelayProxy(loaded);
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
