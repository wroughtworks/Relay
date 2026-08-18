package dev.relay.proxy;

import dev.relay.auth.GameProfile;
import dev.relay.config.BalanceStrategy;
import dev.relay.config.RelayConfig.ServerEntry;
import dev.relay.net.MinecraftConnection;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolVersion;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a group decides which of its members a player should go to.
 *
 * <p>The assertions worth making are about the whole returned order, not just the winner.
 * A group hands back every member with the preferred one first, which is what lets one
 * mechanism serve both balancing and failover: if the pick refuses, the next entry is
 * already the second-best choice rather than an arbitrary one.
 */
class ServerGroupTest {

    @Test
    void leastPlayersPrefersTheEmptiestMember() {
        RegisteredServer busy = server("survival-01");
        RegisteredServer quiet = server("survival-02");
        fill(busy, 5);
        fill(quiet, 1);

        ServerGroup group = new ServerGroup("survival", List.of(busy, quiet));

        assertEquals(List.of(quiet, busy), group.ordered(BalanceStrategy.LEAST_PLAYERS),
                "the emptier server should lead, and the busier one should still be offered after it");
        assertEquals(6, group.playerCount());
    }

    /**
     * Equal counts keep configured order.
     *
     * <p>An empty network is the state a group spends its first minutes in, and picking
     * arbitrarily there would make every restart of the proxy fill a different server
     * first for no reason anyone could see.
     */
    @Test
    void leastPlayersBreaksTiesByConfiguredOrder() {
        RegisteredServer first = server("survival-01");
        RegisteredServer second = server("survival-02");
        ServerGroup group = new ServerGroup("survival", List.of(first, second));

        assertEquals(List.of(first, second), group.ordered(BalanceStrategy.LEAST_PLAYERS));
    }

    @Test
    void roundRobinAdvancesOnEveryRequest() {
        RegisteredServer first = server("pvp-01");
        RegisteredServer second = server("pvp-02");
        RegisteredServer third = server("pvp-03");
        ServerGroup group = new ServerGroup("pvp", List.of(first, second, third));

        assertEquals(first, group.ordered(BalanceStrategy.ROUND_ROBIN).get(0));
        assertEquals(second, group.ordered(BalanceStrategy.ROUND_ROBIN).get(0));
        assertEquals(third, group.ordered(BalanceStrategy.ROUND_ROBIN).get(0));
        assertEquals(first, group.ordered(BalanceStrategy.ROUND_ROBIN).get(0), "the turn should wrap");
    }

    /** Rotation, not truncation: the members behind the pick must still be offered. */
    @Test
    void roundRobinStillOffersEveryMember() {
        RegisteredServer first = server("pvp-01");
        RegisteredServer second = server("pvp-02");
        RegisteredServer third = server("pvp-03");
        ServerGroup group = new ServerGroup("pvp", List.of(first, second, third));

        group.ordered(BalanceStrategy.ROUND_ROBIN);
        assertEquals(List.of(second, third, first), group.ordered(BalanceStrategy.ROUND_ROBIN));
    }

    @Test
    void firstAvailableNeverReorders() {
        RegisteredServer first = server("lobby-01");
        RegisteredServer second = server("lobby-02");
        fill(first, 20);
        ServerGroup group = new ServerGroup("lobby", List.of(first, second));

        assertEquals(List.of(first, second), group.ordered(BalanceStrategy.FIRST_AVAILABLE),
                "first-available is a priority list, so load must not move anything");
    }

    @Test
    void randomKeepsEveryMemberAndEventuallyVaries() {
        RegisteredServer first = server("a");
        RegisteredServer second = server("b");
        RegisteredServer third = server("c");
        ServerGroup group = new ServerGroup("g", List.of(first, second, third));

        Set<List<RegisteredServer>> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            List<RegisteredServer> ordered = group.ordered(BalanceStrategy.RANDOM);
            assertEquals(3, Set.copyOf(ordered).size(), "every member must appear exactly once");
            seen.add(ordered);
        }
        assertTrue(seen.size() > 1, "200 draws from three members that never differ is not random");
    }

    @Test
    void aGroupMustHaveMembers() {
        assertThrows(IllegalArgumentException.class, () -> new ServerGroup("empty", List.of()));
    }

    @Test
    void balanceStrategyNamesAreParsedAndBadOnesNamed() {
        assertEquals(BalanceStrategy.LEAST_PLAYERS, BalanceStrategy.parse("least-players"));
        assertEquals(BalanceStrategy.ROUND_ROBIN, BalanceStrategy.parse("ROUND_ROBIN"),
                "underscores and case are common enough to accept");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> BalanceStrategy.parse("least-lag"));
        assertTrue(thrown.getMessage().contains("least-players"),
                "an unknown strategy should list the ones that exist, got: " + thrown.getMessage());
        assertNotEquals("", thrown.getMessage());
    }

    // ------------------------------------------------------------ helpers

    private static RegisteredServer server(String name) {
        return new RegisteredServer(new ServerEntry(name, new InetSocketAddress("127.0.0.1", 25565)));
    }

    /** Puts {@code count} players on a server, which is all the balancer reads. */
    private static void fill(RegisteredServer server, int count) {
        List<ConnectedPlayer> players = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            players.add(player("player" + i));
        }
        for (ConnectedPlayer player : players) {
            server.addPlayer(player);
        }
    }

    private static ConnectedPlayer player(String name) {
        MinecraftConnection connection = new MinecraftConnection(
                new EmbeddedChannel(), PacketDirection.SERVERBOUND, null, null);
        return new ConnectedPlayer(connection,
                new GameProfile(UUID.randomUUID(), name, List.of()),
                ProtocolVersion.MINECRAFT_1_20_2, null);
    }
}
