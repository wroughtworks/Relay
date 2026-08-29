package dev.relay.store;

import dev.relay.auth.GameProfile;
import dev.relay.net.MinecraftConnection;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolVersion;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.ProxyEvents;
import dev.relay.proxy.RegisteredServer;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Connection history, end to end: events in, sessions and visits out.
 *
 * <p>Spec &sect;9.1 asks the Players view for connection history, and the shape it has to
 * answer is not "was Carson online" but "where did Carson go" -- lobby, then survival-01,
 * then lobby, with the times. That is one session row and several visit rows, and the
 * thing worth testing is that a switch closes one visit and opens the next rather than
 * leaving both open or neither.
 */
class HistoryTest {

    @Test
    void aVisitPerBackendIsRecordedInOrder(@TempDir Path dir) throws Exception {
        try (Database database = new Database(dir.resolve("relay.db"))) {
            database.start();
            History history = new History(database, 14);
            ProxyEvents.Listener listener = history.listener();

            ConnectedPlayer player = player("Carson");
            RegisteredServer lobby = server("lobby", 25566);
            RegisteredServer survival = server("survival-01", 25568);

            listener.onPlayerEvent(new ProxyEvents.PlayerEvent(
                    ProxyEvents.Kind.PLAYER_CONNECTED, player, null, lobby));
            listener.onPlayerEvent(new ProxyEvents.PlayerEvent(
                    ProxyEvents.Kind.PLAYER_SWITCHED_SERVER, player, lobby, survival));
            listener.onPlayerEvent(new ProxyEvents.PlayerEvent(
                    ProxyEvents.Kind.PLAYER_SWITCHED_SERVER, player, survival, lobby));
            listener.onPlayerEvent(new ProxyEvents.PlayerEvent(
                    ProxyEvents.Kind.PLAYER_DISCONNECTED, player, lobby, null));

            settle(database, 8);

            List<History.Session> sessions = history.sessions(player.uuid().toString(), 10);
            assertEquals(1, sessions.size(), "one visit to the network is one session");

            History.Session session = sessions.get(0);
            assertEquals("Carson", session.username());
            assertNotNull(session.disconnectedAt(), "a player who left should have an end time");
            assertEquals(List.of("lobby", "survival-01", "lobby"),
                    session.visits().stream().map(History.Visit::server).toList(),
                    "the order is the history; a set of servers would not answer the question");

            for (History.Visit visit : session.visits()) {
                assertNotNull(visit.leftAt(), visit.server() + " should have been closed");
                assertTrue(visit.leftAt() >= visit.joinedAt());
            }
        }
    }

    @Test
    void aPlayerStillOnlineHasAnOpenVisit(@TempDir Path dir) throws Exception {
        try (Database database = new Database(dir.resolve("relay.db"))) {
            database.start();
            History history = new History(database, 14);
            ConnectedPlayer player = player("Still");
            history.listener().onPlayerEvent(new ProxyEvents.PlayerEvent(
                    ProxyEvents.Kind.PLAYER_CONNECTED, player, null, server("lobby", 25566)));

            settle(database, 2);

            History.Session session = history.sessions(player.uuid().toString(), 10).get(0);
            assertNull(session.disconnectedAt(), "they have not left yet");
            assertNull(session.visits().get(0).leftAt());
        }
    }

    /**
     * A proxy that stops leaves nothing open.
     *
     * <p>Otherwise the history fills with players who apparently never left, and the next
     * run cannot tell those from the ones still on.
     */
    @Test
    void shutdownClosesWhatThisRunOpened(@TempDir Path dir) throws Exception {
        try (Database database = new Database(dir.resolve("relay.db"))) {
            database.start();
            History history = new History(database, 14);
            ConnectedPlayer player = player("Interrupted");
            history.listener().onPlayerEvent(new ProxyEvents.PlayerEvent(
                    ProxyEvents.Kind.PLAYER_CONNECTED, player, null, server("lobby", 25566)));
            settle(database, 2);

            history.closeOpenSessions();

            History.Session session = history.sessions(player.uuid().toString(), 10).get(0);
            assertNotNull(session.disconnectedAt(), "the session should have been closed at shutdown");
            assertNotNull(session.visits().get(0).leftAt());
        }
    }

    @Test
    void sessionsSurviveTheProxyThatWroteThem(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("relay.db");
        ConnectedPlayer player = player("Persistent");
        try (Database database = new Database(file)) {
            database.start();
            History history = new History(database, 14);
            history.listener().onPlayerEvent(new ProxyEvents.PlayerEvent(
                    ProxyEvents.Kind.PLAYER_CONNECTED, player, null, server("lobby", 25566)));
            history.listener().onPlayerEvent(new ProxyEvents.PlayerEvent(
                    ProxyEvents.Kind.PLAYER_DISCONNECTED, player, server("lobby", 25566), null));
            settle(database, 3);
        }

        try (Database reopened = new Database(file)) {
            reopened.start();
            History history = new History(reopened, 14);
            assertEquals(1, history.sessions(player.uuid().toString(), 10).size(),
                    "history that does not outlive the process is not history");
        }
    }

    @Test
    void historyIsEmptyRatherThanMissingWhenNothingHappened(@TempDir Path dir) throws Exception {
        try (Database database = new Database(dir.resolve("relay.db"))) {
            database.start();
            assertTrue(new History(database, 14).sessions(null, 10).isEmpty());
        }
    }

    // ------------------------------------------------------------------- helpers

    /** Waits for the asynchronous writer, which is asynchronous precisely so it can lag. */
    private static void settle(Database database, int expected) throws InterruptedException {
        for (int i = 0; i < 100 && database.written() < expected; i++) {
            Thread.sleep(50);
        }
    }

    private static ConnectedPlayer player(String name) {
        MinecraftConnection connection = new MinecraftConnection(
                new EmbeddedChannel(), PacketDirection.SERVERBOUND, null, null);
        return new ConnectedPlayer(connection,
                new GameProfile(UUID.randomUUID(), name, List.of()),
                ProtocolVersion.MINECRAFT_1_20_2, null);
    }

    private static RegisteredServer server(String name, int port) {
        return new RegisteredServer(new dev.relay.config.RelayConfig.ServerEntry(
                name, new InetSocketAddress("127.0.0.1", port)));
    }
}
