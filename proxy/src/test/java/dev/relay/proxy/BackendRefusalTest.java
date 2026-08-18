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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reproduces the two ways a backend can be listening yet still refuse a player, and
 * asserts the player is told which one happened.
 *
 * <p>Both previously surfaced as the same opaque "could not reach lobby".
 */
class BackendRefusalTest {

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

    /**
     * A backend that accepts the socket then hangs up without answering &mdash; what a
     * still-starting server, or a non-Minecraft service on the port, looks like.
     */
    @Test
    void aBackendThatHangsUpProducesAnActionableReason(@TempDir Path dir) throws Exception {
        CompletableFuture<Void> accepted = listen(connection -> connection.close());
        ByteBuf kick = login(dir, 30_000);
        accepted.get(10, TimeUnit.SECONDS);

        try {
            assertEquals(0x02, ProtocolUtils.readVarInt(kick), "expected a configuration-state disconnect");
            assertTrue(kick.isReadable(), "the kick must carry a reason");
        } finally {
            kick.release();
        }
    }

    /**
     * A backend that accepts the connection, swallows the handshake and never answers.
     *
     * <p>This is what a backend expecting a wrapper protocol Relay does not speak looks
     * like: Paper with {@code proxies.proxy-protocol: true} buffers the bytes waiting for
     * a PROXY header that never arrives, so nothing is refused and nothing is returned.
     * Distinguishing it from a hang-up matters, because "never answered" and "closed on
     * us" have completely different fixes.
     */
    @Test
    void relayGivesUpOnASilentBackend(@TempDir Path dir) throws Exception {
        int readTimeout = 1500;

        // Asserted from the backend's side rather than the player's: the player is
        // waiting on the same read timeout, so racing the two would be flaky.
        CompletableFuture<Long> closedAfter = new CompletableFuture<>();
        CompletableFuture<Void> accepted = listen(connection -> {
            InputStream in = connection.getInputStream();
            in.read(new byte[64]); // Relay's handshake and login start
            long start = System.nanoTime();
            connection.setSoTimeout(10_000);
            // Say nothing. Relay should close this connection rather than wait forever.
            int result = in.read();
            closedAfter.complete((System.nanoTime() - start) / 1_000_000);
            assertEquals(-1, result, "Relay should have closed the connection, not sent more data");
        });

        try {
            login(dir, readTimeout).release();
        } catch (java.io.EOFException expected) {
            // The player's own read timeout may fire first; irrelevant to this assertion.
        }
        accepted.get(15, TimeUnit.SECONDS);

        long elapsed = closedAfter.get(15, TimeUnit.SECONDS);
        assertTrue(elapsed < 10_000, "Relay hung on a silent backend instead of timing out (" + elapsed + "ms)");
    }

    /**
     * A backend that speaks the protocol and refuses the login on purpose &mdash; the
     * version-mismatch case. Its own wording must reach the player.
     */
    @Test
    void aBackendRejectionIsPassedThroughToThePlayer(@TempDir Path dir) throws Exception {
        CompletableFuture<Void> accepted = listen(connection -> {
            InputStream in = connection.getInputStream();
            readFrame(in).release(); // handshake
            readFrame(in).release(); // login start

            ByteBuf disconnect = Unpooled.buffer();
            disconnect.writeByte(0x00); // login disconnect
            ProtocolUtils.writeString(disconnect, "{\"text\":\"Outdated client! Please use 1.21.4\"}");
            writeFrame(connection.getOutputStream(), disconnect);
            connection.getOutputStream().flush();
            // Give the proxy a moment to read it before the socket drops.
            Thread.sleep(250);
            connection.close();
        });

        ByteBuf kick = login(dir, 30_000);
        accepted.get(10, TimeUnit.SECONDS);

        try {
            assertEquals(0x02, ProtocolUtils.readVarInt(kick));
            // The reason travels as network NBT at this version; the backend's own text
            // must survive into it rather than being replaced by a generic message.
            String raw = kick.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(raw.contains("Outdated client"),
                    "the backend's own reason should reach the player, got: " + raw);
        } finally {
            kick.release();
        }
    }

    // ------------------------------------------------------------ harness

    private interface BackendBehaviour {
        void handle(Socket connection) throws Exception;
    }

    /** Starts the fake backend and returns a future that completes once it has run. */
    private CompletableFuture<Void> listen(BackendBehaviour behaviour) throws IOException {
        backend = new ServerSocket();
        backend.bind(new InetSocketAddress("127.0.0.1", 0));

        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try (Socket connection = backend.accept()) {
                connection.setSoTimeout(10_000);
                behaviour.handle(connection);
                done.complete(null);
            } catch (Exception e) {
                done.completeExceptionally(e);
            }
        }, "fake-backend");
        thread.setDaemon(true);
        thread.start();
        return done;
    }

    /**
     * Runs a full offline login and returns the disconnect frame the player receives.
     *
     * @param readTimeout how long Relay waits on a silent backend, in milliseconds; kept
     *                    short for the timeout case so the test does not take 30 seconds
     */
    private ByteBuf login(Path dir, int readTimeout) throws Exception {
        int port = freePort();
        Path config = dir.resolve("relay.toml");
        Files.writeString(config, """
                bind = "127.0.0.1:%d"
                motd = "test"
                online-mode = false
                forwarding-mode = "none"
                health.enabled = false
                compression-threshold = -1
                read-timeout = %d

                [servers]
                lobby = "127.0.0.1:%d"
                """.formatted(port, readTimeout, backend.getLocalPort()));

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

            return readFrame(socket.getInputStream());
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
