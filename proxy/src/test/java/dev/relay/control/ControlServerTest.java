package dev.relay.control;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.relay.auth.GameProfile;
import dev.relay.config.ConfigLoader;
import dev.relay.net.MinecraftConnection;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolVersion;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RelayProxy;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The channel companions see the proxy through.
 *
 * <p>Everything outside the proxy — the dashboard now, a Discord bot later — learns what
 * it knows here and nowhere else. That makes this the whole contract between Relay and
 * anything watching it, so the tests drive it over a real socket rather than calling the
 * handler: what is at risk is the framing, the handshake, and the refusal, none of which
 * a direct call exercises.
 */
class ControlServerTest {

    private RelayProxy proxy;

    @AfterEach
    void stop() {
        if (proxy != null) {
            proxy.shutdown();
        }
    }

    @Test
    void answersQueriesOnceACompanionHasProvedItself(@TempDir Path dir) throws Exception {
        int port = start(dir);

        try (Companion companion = new Companion(port)) {
            JsonObject welcome = companion.hello(proxy.control().token());
            assertEquals("welcome", welcome.get("type").getAsString());
            assertEquals(ControlServer.PROTOCOL_VERSION, welcome.get("protocol").getAsInt());

            JsonObject overview = companion.query(1, "overview");
            assertEquals("result", overview.get("type").getAsString());
            assertEquals(1, overview.get("id").getAsInt());
            assertEquals(2, overview.getAsJsonObject("data").get("servers").getAsInt());

            JsonObject servers = companion.query(2, "servers");
            assertEquals(2, servers.getAsJsonArray("data").size());
            assertEquals("survival-01",
                    servers.getAsJsonArray("data").get(0).getAsJsonObject().get("name").getAsString());
        }
    }

    /**
     * The token is the whole access control, so a wrong one must end the connection.
     *
     * <p>Loopback alone is not enough on a shared machine: any local process could
     * otherwise read the player list. Refusing has to close rather than merely decline,
     * or a wrong token becomes something to retry against.
     */
    @Test
    void refusesAndClosesOnABadToken(@TempDir Path dir) throws Exception {
        int port = start(dir);

        try (Companion companion = new Companion(port)) {
            assertNull(companion.hello("not-the-token"),
                    "a bad token should close the connection, not just be ignored");
        }
    }

    /** A query before the handshake gets nothing, whatever it asks for. */
    @Test
    void refusesQueriesBeforeTheHandshake(@TempDir Path dir) throws Exception {
        int port = start(dir);

        try (Companion companion = new Companion(port)) {
            companion.send("{\"type\":\"query\",\"id\":1,\"what\":\"players\"}");
            assertNull(companion.readLine(), "an unauthenticated query should be answered by a close");
        }
    }

    /**
     * Events are pushed unrequested, on the same connection as the answers.
     *
     * <p>Spec 9.4 asks for one channel multiplexing every event type. A companion holding
     * one socket and reading one shape is the point; a socket per kind would multiply
     * connections by the number of things worth watching.
     */
    @Test
    void pushesEventsToWatchingCompanions(@TempDir Path dir) throws Exception {
        int port = start(dir);

        try (Companion companion = new Companion(port)) {
            companion.hello(proxy.control().token());

            ConnectedPlayer player = player("Tester");
            proxy.players().add(player);
            proxy.events().playerConnected(player, proxy.server("survival-01").orElseThrow());

            JsonObject event = companion.readJson();
            assertEquals("event", event.get("type").getAsString());
            assertEquals("PLAYER_CONNECTED", event.get("kind").getAsString());
            assertEquals("Tester", event.getAsJsonObject("player").get("username").getAsString());
            assertEquals("survival-01", event.get("to").getAsString());
        }
    }

    /**
     * An unknown message costs a feature, not the connection.
     *
     * <p>A companion built against a newer proxy will send things this one has never
     * heard of. Closing on those would make every upgrade a coordinated one.
     */
    @Test
    void ignoresMessagesItDoesNotUnderstand(@TempDir Path dir) throws Exception {
        int port = start(dir);

        try (Companion companion = new Companion(port)) {
            companion.hello(proxy.control().token());
            companion.send("{\"type\":\"somethingFromTheFuture\",\"payload\":42}");

            // Still answering afterwards is the assertion.
            JsonObject result = companion.query(9, "groups");
            assertEquals("result", result.get("type").getAsString());
            assertEquals(9, result.get("id").getAsInt());
        }
    }

    @Test
    void staysShutWhenControlIsDisabled(@TempDir Path dir) throws Exception {
        int port = freePort();
        Files.writeString(dir.resolve("relay.toml"), """
                bind = "127.0.0.1:%d"
                forwarding-mode = "none"
                health.enabled = false

                [control]
                enabled = false
                bind = "127.0.0.1:%d"

                [servers]
                lobby = "127.0.0.1:25566"
                """.formatted(freePort(), port));
        proxy = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        proxy.start();

        assertNull(proxy.control(), "nothing should be listening with control disabled");
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new InetSocketAddress("127.0.0.1", port));
        } catch (IOException e) {
            throw new AssertionError("the control port was taken, so it was opened anyway", e);
        }
    }

    // ------------------------------------------------------------ helpers

    private int start(Path dir) throws Exception {
        int controlPort = freePort();
        Files.writeString(dir.resolve("relay.toml"), """
                bind = "127.0.0.1:%d"
                forwarding-mode = "none"
                health.enabled = false

                [control]
                enabled = true
                bind = "127.0.0.1:%d"

                [servers]
                survival-01 = "127.0.0.1:25566"
                survival-02 = "127.0.0.1:25567"
                """.formatted(freePort(), controlPort));
        proxy = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        proxy.start();
        return controlPort;
    }

    private static ConnectedPlayer player(String name) {
        MinecraftConnection connection = new MinecraftConnection(
                new EmbeddedChannel(), PacketDirection.SERVERBOUND, null, null);
        return new ConnectedPlayer(connection,
                new GameProfile(UUID.randomUUID(), name, List.of()),
                ProtocolVersion.MINECRAFT_1_20_2, null);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    /** A companion, spoken by hand, so the wire format is what is under test. */
    private static final class Companion implements AutoCloseable {

        private final Socket socket;
        private final BufferedReader in;
        private final Writer out;

        Companion(int port) throws IOException {
            socket = new Socket("127.0.0.1", port);
            socket.setSoTimeout(10_000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
        }

        /** @return the welcome, or null if the proxy closed instead */
        JsonObject hello(String token) throws IOException {
            send("{\"type\":\"hello\",\"token\":\"" + token + "\"}");
            String line = readLine();
            return line == null ? null : JsonParser.parseString(line).getAsJsonObject();
        }

        JsonObject query(int id, String what) throws IOException {
            send("{\"type\":\"query\",\"id\":" + id + ",\"what\":\"" + what + "\"}");
            return readJson();
        }

        void send(String json) throws IOException {
            out.write(json);
            out.write('\n');
            out.flush();
        }

        JsonObject readJson() throws IOException {
            String line = readLine();
            if (line == null) {
                throw new AssertionError("the control connection closed when a message was expected");
            }
            return JsonParser.parseString(line).getAsJsonObject();
        }

        String readLine() throws IOException {
            return in.readLine();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
