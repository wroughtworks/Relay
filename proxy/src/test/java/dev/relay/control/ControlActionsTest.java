package dev.relay.control;

import dev.relay.auth.GameProfile;
import dev.relay.config.ConfigLoader;
import dev.relay.health.BackendHealth;
import dev.relay.net.MinecraftConnection;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolVersion;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half of the control channel that can change something.
 *
 * <p>Everything else a companion can reach is read-only by construction: the worst a bug in
 * it can do is show the wrong number. These four can drain a backend out of rotation, move
 * people between servers and disconnect them, so what is worth testing is mostly the
 * refusals &mdash; the cases where an action should decline and say why, rather than half
 * happening.
 */
class ControlActionsTest {

    private RelayProxy proxy;
    private ControlActions actions;

    @AfterEach
    void stop() {
        if (proxy != null) {
            proxy.shutdown();
        }
    }

    // ------------------------------------------------------------ drain

    @Test
    void drainingTakesABackendOutOfRotationAndPuttingItBackDoesNotClaimHealth(@TempDir Path dir)
            throws Exception {
        start(dir);
        RegisteredServer lobby = proxy.server("lobby").orElseThrow();

        ControlActions.Result on = actions.drain("carson", "lobby", true);
        assertTrue(on.ok(), on.message());
        assertEquals(BackendHealth.State.DRAINING, lobby.health().state());
        assertFalse(lobby.acceptsNewPlayers(), "a draining backend must stop taking new players");

        ControlActions.Result off = actions.drain("carson", "lobby", false);
        assertTrue(off.ok(), off.message());
        // UNKNOWN rather than HEALTHY: the next check decides. Claiming health the proxy
        // has not confirmed would be inventing it.
        assertEquals(BackendHealth.State.UNKNOWN, lobby.health().state());
    }

    /**
     * Asking for a state it is already in is a refusal, not a quiet success.
     *
     * <p>The operator asked for a change and did not get one. "Already draining, with 12
     * still on it" is the answer that tells them what they actually need to know.
     */
    @Test
    void drainingSomethingAlreadyDrainingIsRefusedAndSaysWhy(@TempDir Path dir) throws Exception {
        start(dir);
        actions.drain("carson", "lobby", true);

        ControlActions.Result again = actions.drain("carson", "lobby", true);
        assertFalse(again.ok());
        assertTrue(again.message().contains("already draining"), again.message());

        ControlActions.Result never = actions.drain("carson", "survival-01", false);
        assertFalse(never.ok());
        assertTrue(never.message().contains("not draining"), never.message());
    }

    /**
     * A group cannot be drained, and the refusal has to explain why rather than say "no
     * such backend" about a name the operator can see on the page.
     */
    @Test
    void aGroupCannotBeDrained(@TempDir Path dir) throws Exception {
        start(dir);

        ControlActions.Result result = actions.drain("carson", "survival", true);

        assertFalse(result.ok());
        assertTrue(result.message().contains("individually"), result.message());
        assertEquals(BackendHealth.State.UNKNOWN,
                proxy.server("survival-01").orElseThrow().health().state(),
                "refusing must not have drained a member on the way past");
    }

    @Test
    void drainingSomethingThatDoesNotExistIsRefused(@TempDir Path dir) throws Exception {
        start(dir);

        ControlActions.Result result = actions.drain("carson", "nowhere", true);

        assertFalse(result.ok());
        assertTrue(result.message().contains("no backend called"), result.message());
    }

    // ------------------------------------------------------------ send

    @Test
    void sendingSomebodyWhoIsNotOnlineIsRefused(@TempDir Path dir) throws Exception {
        start(dir);

        ControlActions.Result result = actions.send("carson", "Nobody", "lobby");

        assertFalse(result.ok());
        assertTrue(result.message().contains("not online"), result.message());
    }

    @Test
    void sendingToSomewhereThatDoesNotExistIsRefused(@TempDir Path dir) throws Exception {
        start(dir);
        join("Tester", "lobby");

        ControlActions.Result result = actions.send("carson", "Tester", "atlantis");

        assertFalse(result.ok());
        assertTrue(result.message().contains("no server or group"), result.message());
    }

    /**
     * Somebody already there is a refusal, so a bulk move is idempotent.
     *
     * <p>For a group that means any member: someone on {@code survival-02} has already
     * arrived at {@code survival}, and shunting them to {@code survival-01} would be a
     * round trip through configuration that changes nothing they can see.
     */
    @Test
    void sendingSomebodyWhereTheyAlreadyAreIsRefused(@TempDir Path dir) throws Exception {
        start(dir);
        join("Tester", "survival-02");

        assertFalse(actions.send("carson", "Tester", "survival-02").ok());
        ControlActions.Result toGroup = actions.send("carson", "Tester", "survival");
        assertFalse(toGroup.ok(), "a member of the group counts as already at the group");
        assertTrue(toGroup.message().contains("already on"), toGroup.message());
    }

    // ------------------------------------------------------------ evacuate

    @Test
    void evacuatingAnEmptyBackendIsRefusedRatherThanReportedAsSuccess(@TempDir Path dir)
            throws Exception {
        start(dir);

        ControlActions.Result result = actions.evacuate("carson", "lobby", "survival");

        assertFalse(result.ok());
        assertTrue(result.message().contains("already empty"), result.message());
    }

    /**
     * Evacuating somewhere that includes the server being emptied would move nobody.
     *
     * <p>The obvious mistake at three in the morning: drain {@code survival-01}, then send
     * its players to {@code survival}, which contains it. Balancing would put some of them
     * straight back, and the backend would never empty.
     */
    @Test
    void evacuatingIntoAGroupContainingTheSourceIsRefused(@TempDir Path dir) throws Exception {
        start(dir);
        join("Tester", "survival-01");

        ControlActions.Result result = actions.evacuate("carson", "survival-01", "survival");

        assertFalse(result.ok());
        assertTrue(result.message().contains("includes"), result.message());
    }

    @Test
    void evacuatingMoreThanTheLimitIsRefusedWithAWayForward(@TempDir Path dir) throws Exception {
        start(dir);
        for (int i = 0; i <= ControlActions.MAX_MOVED; i++) {
            join("Player%03d".formatted(i), "survival-01");
        }

        ControlActions.Result result = actions.evacuate("carson", "survival-01", "lobby");

        assertFalse(result.ok());
        assertTrue(result.message().contains("Drain it"),
                "a refusal with no way forward is just an obstacle: " + result.message());
    }

    // ------------------------------------------------------------ kick

    @Test
    void kickingSomebodyWhoIsNotOnlineIsRefused(@TempDir Path dir) throws Exception {
        start(dir);

        ControlActions.Result result = actions.kick("carson", "Nobody", "because");

        assertFalse(result.ok());
        assertTrue(result.message().contains("not online"), result.message());
    }

    @Test
    void kickingDisconnectsThemAndEchoesTheReason(@TempDir Path dir) throws Exception {
        start(dir);
        ConnectedPlayer player = join("Tester", "lobby");

        ControlActions.Result result = actions.kick("carson", "tester", "being a nuisance");

        assertTrue(result.ok(), result.message());
        assertTrue(result.message().contains("being a nuisance"), result.message());
        assertFalse(player.connection().channel().isActive(), "they are still connected");
    }

    /** A kick with no reason still gives the player something to read. */
    @Test
    void aKickWithNoReasonStillSaysSomething(@TempDir Path dir) throws Exception {
        start(dir);
        join("Tester", "lobby");

        ControlActions.Result result = actions.kick("carson", "Tester", "   ");

        assertTrue(result.ok());
        assertTrue(result.message().contains("administrator"), result.message());
    }

    // ------------------------------------------------------------ helpers

    private void start(Path dir) throws Exception {
        Files.writeString(dir.resolve("relay.toml"), """
                bind = "127.0.0.1:%d"
                node-name = "test-node"
                forwarding-mode = "none"
                health.enabled = false
                control.enabled = false
                storage.enabled = false

                [servers]
                lobby = "127.0.0.1:25566"
                survival-01 = "127.0.0.1:25567"
                survival-02 = "127.0.0.1:25568"
                """.formatted(freePort()));
        proxy = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        proxy.start();
        actions = new ControlActions(proxy);
    }

    private ConnectedPlayer join(String name, String server) {
        MinecraftConnection connection = new MinecraftConnection(
                new EmbeddedChannel(), PacketDirection.SERVERBOUND, null, null);
        ConnectedPlayer player = new ConnectedPlayer(connection,
                new GameProfile(UUID.randomUUID(), name, List.of()),
                ProtocolVersion.MINECRAFT_1_20_2, null);
        ServerConnection connected =
                new ServerConnection(proxy.server(server).orElseThrow(), player);
        player.setConnectedServer(connected);
        // Established, not merely connected. A backend's player set is only populated at
        // that point, which is what "evacuate" counts -- someone still mid-switch is not
        // on the server yet and moving them again would race the move already in flight.
        connected.markEstablished();
        proxy.players().add(player);
        return player;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }
}
