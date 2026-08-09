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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
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
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the modern-forwarding exchange the way a Paper backend does, against the bytes
 * that actually leave the proxy.
 *
 * <p>{@code ModernForwardingTest} checks the signing helper in isolation. That cannot
 * catch a fault in everything wrapped around it: the login plugin channel name, the
 * transaction id echoed back, the success flag, or the packet framing. Any of those would
 * make a backend report exactly what a wrong secret reports &mdash; Paper's
 * "Unable to verify player details" &mdash; so the whole path is driven here and checked
 * with Paper's own algorithm.
 */
class ModernForwardingHandshakeTest {

    private static final ProtocolVersion VERSION = ProtocolVersion.MINECRAFT_1_20_2;
    private static final String SECRET = "y4dfkEz9IR4kdTZc47HHm3mbi0fud/cKk6o/BxP1HP4";
    private static final String CHANNEL = "velocity:player_info";
    private static final int TRANSACTION_ID = 99;

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
    void forwardedProfilePassesPaperStyleVerification(@TempDir Path dir) throws Exception {
        CompletableFuture<ByteBuf> response = runBackend();
        login(dir);

        ByteBuf answer = response.get(15, TimeUnit.SECONDS);
        try {
            assertEquals(0x02, ProtocolUtils.readVarInt(answer), "expected a Login Plugin Response");
            assertEquals(TRANSACTION_ID, ProtocolUtils.readVarInt(answer),
                    "the backend's transaction id must be echoed back, or it ignores the answer");
            assertTrue(answer.readBoolean(), "the response must be flagged successful");

            byte[] payload = new byte[answer.readableBytes()];
            answer.readBytes(payload);

            // Exactly what Paper's VelocityProxy.checkIntegrity does.
            byte[] signature = Arrays.copyOfRange(payload, 0, 32);
            byte[] data = Arrays.copyOfRange(payload, 32, payload.length);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            assertTrue(MessageDigest.isEqual(signature, mac.doFinal(data)),
                    "Paper would reject this with 'Unable to verify player details'");

            // And what it reads afterwards to build the profile.
            ByteBuf body = Unpooled.wrappedBuffer(data);
            try {
                assertTrue(ProtocolUtils.readVarInt(body) <= 4, "forwarding version must be one Paper supports");
                assertEquals("127.0.0.1", ProtocolUtils.readString(body), "the player's address");
                assertEquals(GameProfile.offline("Tester").uuid(), ProtocolUtils.readUuid(body));
                assertEquals("Tester", ProtocolUtils.readString(body));
                assertEquals(0, ProtocolUtils.readVarInt(body), "offline profiles carry no properties");
                assertTrue(!body.isReadable(), "trailing bytes would desynchronise Paper's profile read");
            } finally {
                body.release();
            }
        } finally {
            answer.release();
        }
    }

    /** Stands in for Paper: asks for the player info, then hands back what it got. */
    private CompletableFuture<ByteBuf> runBackend() throws IOException {
        backend = new ServerSocket();
        backend.bind(new InetSocketAddress("127.0.0.1", 0));

        CompletableFuture<ByteBuf> received = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try (Socket connection = backend.accept()) {
                connection.setSoTimeout(10_000);
                InputStream in = connection.getInputStream();
                OutputStream out = connection.getOutputStream();

                readFrame(in).release(); // handshake
                readFrame(in).release(); // login start

                ByteBuf request = Unpooled.buffer();
                request.writeByte(0x04); // clientbound Login Plugin Request
                ProtocolUtils.writeVarInt(request, TRANSACTION_ID);
                ProtocolUtils.writeString(request, CHANNEL);
                request.writeByte(4); // maximum forwarding version this backend supports
                writeFrame(out, request);
                out.flush();

                received.complete(readFrame(in));
                Thread.sleep(500);
            } catch (Exception e) {
                received.completeExceptionally(e);
            }
        }, "fake-backend");
        thread.setDaemon(true);
        thread.start();
        return received;
    }

    private void login(Path dir) throws Exception {
        int port = freePort();
        Path config = dir.resolve("relay.toml");
        Files.writeString(config, """
                bind = "127.0.0.1:%d"
                motd = "test"
                online-mode = false
                forwarding-mode = "modern"
                forwarding-secret = "%s"
                compression-threshold = -1

                [servers]
                lobby = "127.0.0.1:%d"
                """.formatted(port, SECRET, backend.getLocalPort()));

        proxy = new RelayProxy(ConfigLoader.load(config));
        proxy.start();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();

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

            readFrame(socket.getInputStream()).release(); // login success
            writeFrame(out, Unpooled.buffer().writeByte(0x03)); // login acknowledged
            out.flush();

            // Hold the player socket open while the backend exchange completes.
            Thread.sleep(1500);
        }
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
