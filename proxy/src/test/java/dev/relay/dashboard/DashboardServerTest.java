package dev.relay.dashboard;

import com.google.gson.JsonArray;
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

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The dashboard's read-only API, driven over real HTTP and a real WebSocket.
 *
 * <p>Asserting against Javalin's test helpers would prove the handlers return the right
 * objects. What is actually at risk is everything around them: whether Gson is wired in
 * place of the Jackson Javalin expects, whether the bind address is honoured, and whether
 * a browser holding a socket receives anything at all. Those only fail over a socket.
 */
class DashboardServerTest {

    private RelayProxy proxy;

    @AfterEach
    void stop() {
        if (proxy != null) {
            proxy.shutdown();
        }
    }

    @Test
    void servesLiveStateAsJson(@TempDir Path dir) throws Exception {
        int port = start(dir);

        JsonObject overview = getJson(port, "/api/overview").getAsJsonObject();
        assertEquals(0, overview.get("players").getAsInt());
        assertEquals(2, overview.get("servers").getAsInt());
        assertEquals(1, overview.get("groups").getAsInt());
        assertEquals("least-players", overview.get("balance").getAsString());

        JsonArray servers = getJson(port, "/api/servers").getAsJsonArray();
        assertEquals(2, servers.size());
        JsonObject first = servers.get(0).getAsJsonObject();
        assertEquals("survival-01", first.get("name").getAsString());
        assertEquals("survival", first.get("group").getAsString(),
                "a backend's group is what makes the server list readable at a glance");

        JsonArray groups = getJson(port, "/api/groups").getAsJsonArray();
        assertEquals("survival", groups.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(2, groups.get(0).getAsJsonObject().getAsJsonArray("members").size());

        assertEquals(0, getJson(port, "/api/players").getAsJsonArray().size());
    }

    /**
     * The root serves the page, not a 404.
     *
     * <p>It is bundled in the jar rather than deployed beside the proxy, so the dashboard
     * stays one artefact. This asserts the packaging as much as the route: a resources
     * directory that fails to make it into the shadow jar looks exactly like a missing
     * route, and only at runtime.
     */
    @Test
    void servesThePageAtTheRoot(@TempDir Path dir) throws Exception {
        int port = start(dir);

        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("<title>Relay</title>"),
                "the root should be the dashboard page");
        assertTrue(response.body().contains("/api/events"),
                "the page should be wired to the live socket");
    }

    /**
     * Player records carry no IP address.
     *
     * <p>There is no authentication on this API yet. Saying who is online is one thing;
     * pairing usernames with home addresses to anyone who can reach the port is another,
     * and it would be an easy field to add without noticing the difference.
     */
    @Test
    void playerRecordsDoNotCarryAddresses(@TempDir Path dir) throws Exception {
        int port = start(dir);
        proxy.players().add(player("Tester"));

        JsonArray players = getJson(port, "/api/players").getAsJsonArray();
        assertEquals(1, players.size());
        JsonObject view = players.get(0).getAsJsonObject();
        assertEquals("Tester", view.get("username").getAsString());
        // Present and null, not absent: the field's shape must not depend on its value,
        // or a client cannot tell "mid-switch" from "this API changed".
        assertTrue(view.has("server"), "the field should always be present");
        assertTrue(view.get("server").isJsonNull(), "a player with no backend is mid-switch, not on one");

        String raw = view.toString().toLowerCase(java.util.Locale.ROOT);
        assertFalse(raw.contains("127.0.0.1") || raw.contains("address") || raw.contains("\"ip\""),
                "no address should reach an unauthenticated endpoint, got: " + view);
    }

    /**
     * One socket carries every event type, per spec 9.4.
     *
     * <p>The event is fired the way a session handler fires it, rather than by driving a
     * whole login, because what is under test is the fan-out and the envelope: a socket
     * per event type is the mistake this shape exists to avoid.
     */
    @Test
    void pushesEventsToAWatchingClient(@TempDir Path dir) throws Exception {
        int port = start(dir);

        List<String> received = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> gotOne = new CompletableFuture<>();
        WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/events"), new WebSocket.Listener() {
                    @Override
                    public java.util.concurrent.CompletionStage<?> onText(WebSocket webSocket,
                                                                          CharSequence data, boolean last) {
                        received.add(data.toString());
                        gotOne.complete(null);
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(10, TimeUnit.SECONDS);

        ConnectedPlayer player = player("Tester");
        proxy.players().add(player);
        proxy.events().playerConnected(player, proxy.server("survival-01").orElseThrow());

        gotOne.get(10, TimeUnit.SECONDS);
        JsonObject event = JsonParser.parseString(received.get(0)).getAsJsonObject();
        assertEquals("PLAYER_CONNECTED", event.get("type").getAsString());
        assertEquals("Tester", event.getAsJsonObject("player").get("username").getAsString());
        assertEquals("survival-01", event.get("to").getAsString());
        assertTrue(event.get("from").isJsonNull(), "a first join comes from nowhere");

        socket.abort();
    }

    /** With the dashboard off, nothing is listening at all. */
    @Test
    void staysOffUnlessAskedFor(@TempDir Path dir) throws Exception {
        int port = freePort();
        Files.writeString(dir.resolve("relay.toml"), """
                bind = "127.0.0.1:%d"
                forwarding-mode = "none"

                [servers]
                lobby = "127.0.0.1:25566"
                """.formatted(freePort()));
        proxy = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        proxy.start();

        assertThrowsConnectionRefused(port);
    }

    // ------------------------------------------------------------ helpers

    private int start(Path dir) throws Exception {
        int dashboardPort = freePort();
        Files.writeString(dir.resolve("relay.toml"), """
                bind = "127.0.0.1:%d"
                forwarding-mode = "none"
                balance = "least-players"

                [dashboard]
                enabled = true
                bind = "127.0.0.1:%d"

                [servers]
                survival-01 = "127.0.0.1:25566"
                survival-02 = "127.0.0.1:25567"
                """.formatted(freePort(), dashboardPort));

        proxy = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        proxy.start();
        return dashboardPort;
    }

    private static com.google.gson.JsonElement getJson(int port, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + " should have answered");
        return JsonParser.parseString(response.body());
    }

    private static void assertThrowsConnectionRefused(int port) {
        try (ServerSocket probe = new ServerSocket()) {
            probe.bind(new java.net.InetSocketAddress("127.0.0.1", port));
        } catch (IOException e) {
            throw new AssertionError("port " + port + " was taken, so something started that should not have", e);
        }
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
            socket.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }
}
