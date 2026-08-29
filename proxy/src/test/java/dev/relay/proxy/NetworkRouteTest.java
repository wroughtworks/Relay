package dev.relay.proxy;

import dev.relay.auth.GameProfile;
import dev.relay.config.ConfigLoader;
import dev.relay.net.MinecraftConnection;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolVersion;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The path a player's traffic takes, as spec 9.1 asks the Players view to show.
 *
 * <p>"Which server is this player on" is answerable without any of this. "Why are they on
 * that one" is not, and with forced hosts and groups it is the question that actually gets
 * asked. The route is the answer, so what these tests care about is that every hop which
 * influenced the decision appears in it.
 */
class NetworkRouteTest {

    private RelayProxy proxy;

    @AfterEach
    void stop() {
        if (proxy != null) {
            proxy.shutdown();
        }
    }

    @Test
    void namesTheNodeAndTheBackendForADirectPlayer(@TempDir Path dir) throws Exception {
        proxy = start(dir);
        ConnectedPlayer player = player("Tester", "mc.example.com");
        proxy.players().add(player);

        NetworkRoute route = NetworkRoute.of(player, proxy);

        // No backend yet: they are still being connected, and the route says exactly that
        // rather than inventing a destination.
        assertEquals(1, route.hopCount());
        assertEquals(NetworkRoute.Kind.PROXY, route.hops().get(0).kind());
        assertEquals("test-node", route.hops().get(0).name());
        assertEquals("mc.example.com", route.virtualHost());
    }

    /**
     * A grouped backend contributes two hops, not one.
     *
     * <p>{@code survival} deciding and {@code survival-02} answering are different facts.
     * Collapsing them would hide the balancing decision, which is the thing an operator is
     * usually trying to understand.
     */
    @Test
    void aGroupedBackendShowsBothThePoolAndTheMember(@TempDir Path dir) throws Exception {
        proxy = start(dir);
        ConnectedPlayer player = player("Tester", "mc.example.com");
        RegisteredServer member = proxy.server("survival-02").orElseThrow();
        player.setConnectedServer(new ServerConnection(member, player));

        NetworkRoute route = NetworkRoute.of(player, proxy);
        List<NetworkRoute.Hop> hops = route.hops();

        assertEquals(3, route.hopCount());
        assertEquals(NetworkRoute.Kind.PROXY, hops.get(0).kind());
        assertEquals(NetworkRoute.Kind.POOL, hops.get(1).kind());
        assertEquals("survival", hops.get(1).name());
        assertEquals(NetworkRoute.Kind.BACKEND, hops.get(2).kind());
        assertEquals("survival-02", hops.get(2).name());
    }

    /** An ungrouped backend gets no pool hop invented for it. */
    @Test
    void anUngroupedBackendHasNoPoolHop(@TempDir Path dir) throws Exception {
        proxy = start(dir);
        ConnectedPlayer player = player("Tester", null);
        player.setConnectedServer(new ServerConnection(proxy.server("lobby").orElseThrow(), player));

        NetworkRoute route = NetworkRoute.of(player, proxy);

        assertEquals(2, route.hopCount());
        assertTrue(route.hops().stream().noneMatch(hop -> hop.kind() == NetworkRoute.Kind.POOL));
        assertEquals("lobby", route.hops().get(1).name());
    }

    /**
     * The route id is per session, and stable within it.
     *
     * <p>Its value is being the one token that ties a log line to a dashboard row. A id
     * that changed when the player switched server, or was shared between players, would
     * be worse than none.
     */
    @Test
    void theRouteIdIsStablePerSessionAndUniqueBetweenThem(@TempDir Path dir) throws Exception {
        proxy = start(dir);
        ConnectedPlayer player = player("Tester", null);

        String before = NetworkRoute.of(player, proxy).routeId();
        player.setConnectedServer(new ServerConnection(proxy.server("lobby").orElseThrow(), player));
        String after = NetworkRoute.of(player, proxy).routeId();

        assertEquals(before, after, "a switch must not change the id that ties the session together");
        assertNotEquals(before, NetworkRoute.of(player("Other", null), proxy).routeId());
        assertFalse(before.isBlank());
    }

    // ------------------------------------------------------------ helpers

    private RelayProxy start(Path dir) throws Exception {
        Files.writeString(dir.resolve("relay.toml"), """
                bind = "127.0.0.1:%d"
                node-name = "test-node"
                forwarding-mode = "none"
                health.enabled = false
                control.enabled = false

                [servers]
                lobby = "127.0.0.1:25566"
                survival-01 = "127.0.0.1:25567"
                survival-02 = "127.0.0.1:25568"
                """.formatted(freePort()));
        RelayProxy started = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        started.start();
        return started;
    }

    private static ConnectedPlayer player(String name, String virtualHost) {
        MinecraftConnection connection = new MinecraftConnection(
                new EmbeddedChannel(), PacketDirection.SERVERBOUND, null, null);
        return new ConnectedPlayer(connection,
                new GameProfile(UUID.randomUUID(), name, List.of()),
                ProtocolVersion.MINECRAFT_1_20_2, virtualHost);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }
}
