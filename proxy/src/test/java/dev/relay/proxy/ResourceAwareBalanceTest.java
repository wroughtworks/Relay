package dev.relay.proxy;

import dev.relay.auth.GameProfile;
import dev.relay.config.BalanceStrategy;
import dev.relay.config.RelayConfig.ServerEntry;
import dev.relay.health.BackendStats;
import dev.relay.net.MinecraftConnection;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolVersion;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Balancing on what the backends say about themselves (spec &sect;6.2).
 *
 * <p>{@code least-players} treats forty people in a quiet lobby and forty in a redstone
 * farm as the same load. The Relay plugin has been reporting TPS, tick time and heap for a
 * while and the dashboard has been displaying them; this is the strategy that routes on
 * them, so a struggling server stops being handed new arrivals before anyone has to notice
 * and drain it.
 *
 * <p>The property most of these tests are about is the one that makes it safe to turn on:
 * <b>it behaves exactly like least-players until something is meaningfully more loaded
 * than its siblings</b>. A balancer whose decisions an operator cannot predict is worse
 * than a simple one, and a continuous score would have meant tick-time noise deciding
 * every join.
 */
class ResourceAwareBalanceTest {

    /** A healthy report: full tick rate, comfortable tick time, half the heap. */
    private static BackendStats healthy() {
        return stats(20.0, 12.0, 0.5);
    }

    private static BackendStats stats(double tps, double mspt, double heapFraction) {
        long max = 4L * 1024 * 1024 * 1024;
        return new BackendStats(tps, tps, tps, mspt, (long) (max * heapFraction), max,
                0.4, 0, 3600, "1.20.2", System.currentTimeMillis());
    }

    // ------------------------------------------------------- the degradation property

    /**
     * Two healthy servers: the emptier one wins, exactly as least-players would.
     *
     * <p>This is the whole argument for the strategy being switchable on without thought.
     * The measures are flat below the point the dashboard calls warm, so two comfortable
     * servers score identically and the player count is left to decide.
     */
    @Test
    void withNothingStrainedItIsLeastPlayers() {
        RegisteredServer busy = server("survival-01");
        RegisteredServer quiet = server("survival-02");
        busy.setStats(healthy());
        quiet.setStats(stats(20.0, 22.0, 0.7));      // busier, still nowhere near warm
        fill(busy, 20);
        fill(quiet, 4);

        assertEquals(List.of(quiet, busy),
                new ServerGroup("survival", List.of(busy, quiet))
                        .ordered(BalanceStrategy.RESOURCE_AWARE));
    }

    /** And an equal load with equal counts keeps configured order, like every strategy. */
    @Test
    void equalLoadAndEqualCountsKeepConfiguredOrder() {
        RegisteredServer first = server("survival-01");
        RegisteredServer second = server("survival-02");
        first.setStats(healthy());
        second.setStats(healthy());

        assertEquals(List.of(first, second),
                new ServerGroup("survival", List.of(first, second))
                        .ordered(BalanceStrategy.RESOURCE_AWARE));
    }

    /**
     * A struggling server loses even though it has far fewer players.
     *
     * <p>The case least-players gets wrong, and the reason this exists: twenty people on a
     * healthy server is a better destination than two on one doing fourteen ticks a
     * second.
     */
    @Test
    void aStrugglingServerLosesDespiteBeingEmptier() {
        RegisteredServer healthyBusy = server("survival-01");
        RegisteredServer laggingQuiet = server("survival-02");
        healthyBusy.setStats(healthy());
        laggingQuiet.setStats(stats(14.0, 70.0, 0.6));
        fill(healthyBusy, 20);
        fill(laggingQuiet, 2);

        assertEquals(List.of(healthyBusy, laggingQuiet),
                new ServerGroup("survival", List.of(healthyBusy, laggingQuiet))
                        .ordered(BalanceStrategy.RESOURCE_AWARE));
    }

    /**
     * Tick time decides where TPS cannot.
     *
     * <p>TPS saturates at 20 and hides a server spending 45ms of its 50ms budget. Both of
     * these report a full tick rate; only the tick time says one of them is a tick away
     * from falling over.
     */
    @Test
    void tickTimeSeparatesTwoServersBothClaimingTwentyTps() {
        RegisteredServer comfortable = server("survival-01");
        RegisteredServer nearlyFull = server("survival-02");
        comfortable.setStats(stats(20.0, 10.0, 0.5));
        nearlyFull.setStats(stats(20.0, 46.0, 0.5));
        fill(comfortable, 30);

        assertEquals(List.of(comfortable, nearlyFull),
                new ServerGroup("survival", List.of(comfortable, nearlyFull))
                        .ordered(BalanceStrategy.RESOURCE_AWARE),
                "the emptier server is 46ms a tick; sending people there would finish it");
    }

    /**
     * Heap counts only once it is a problem.
     *
     * <p>A JVM at 60% of its heap is using the memory it was given. Reading that as load
     * would prefer whichever server had been restarted most recently, which is not a
     * balancing rule.
     */
    @Test
    void heapBelowTheKneeIsNotLoad() {
        RegisteredServer roomy = server("survival-01");
        RegisteredServer fuller = server("survival-02");
        roomy.setStats(stats(20.0, 12.0, 0.30));
        fuller.setStats(stats(20.0, 12.0, 0.70));
        fill(roomy, 9);

        assertEquals(List.of(fuller, roomy),
                new ServerGroup("survival", List.of(roomy, fuller))
                        .ordered(BalanceStrategy.RESOURCE_AWARE),
                "30% and 70% of heap are both fine, so the player count should have decided");
    }

    @Test
    void heapAboveTheKneeIsLoad() {
        RegisteredServer roomy = server("survival-01");
        RegisteredServer nearlyOutOfHeap = server("survival-02");
        roomy.setStats(stats(20.0, 12.0, 0.40));
        nearlyOutOfHeap.setStats(stats(20.0, 12.0, 0.95));
        fill(roomy, 25);

        assertEquals(List.of(roomy, nearlyOutOfHeap),
                new ServerGroup("survival", List.of(roomy, nearlyOutOfHeap))
                        .ordered(BalanceStrategy.RESOURCE_AWARE));
    }

    /**
     * The worst measure decides, not the average.
     *
     * <p>A server with a healthy heap and a dying tick rate is a server in trouble.
     * Averaging would let the comfortable half hide the half that matters.
     */
    @Test
    void oneBadMeasureIsEnoughToLose() {
        RegisteredServer fine = server("survival-01");
        RegisteredServer goodHeapBadTicks = server("survival-02");
        fine.setStats(healthy());
        goodHeapBadTicks.setStats(stats(16.0, 60.0, 0.10));
        fill(fine, 40);

        assertEquals(List.of(fine, goodHeapBadTicks),
                new ServerGroup("survival", List.of(fine, goodHeapBadTicks))
                        .ordered(BalanceStrategy.RESOURCE_AWARE));
    }

    // ------------------------------------------------------- servers that say nothing

    /**
     * The trap this strategy could easily have walked into.
     *
     * <p>A backend can only report while a player is on it, because a plugin message needs
     * a connection to travel on. So an empty server never reports &mdash; and if "no
     * report" meant "unknown, treat as middling", resource-aware would never send anyone to
     * an empty server, which means it would never start reporting, which means it would
     * stay empty forever. Silence from an empty backend is not ignorance; it is the
     * emptiness, which is the best news there is.
     */
    @Test
    void anEmptyServerThatHasNeverReportedIsStillTheBestDestination() {
        RegisteredServer reportingAndBusy = server("survival-01");
        RegisteredServer freshlyStarted = server("survival-02");
        reportingAndBusy.setStats(healthy());
        fill(reportingAndBusy, 30);
        // freshlyStarted has no stats at all and nobody on it.

        assertEquals(List.of(freshlyStarted, reportingAndBusy),
                new ServerGroup("survival", List.of(reportingAndBusy, freshlyStarted))
                        .ordered(BalanceStrategy.RESOURCE_AWARE),
                "an empty backend cannot report; treating that as unknown would mean it "
                        + "never got a player and so never reported");
    }

    /**
     * A server with players but no report is assumed neither good nor bad.
     *
     * <p>Either the Relay plugin is not installed or the server has stopped ticking, and
     * from outside those look the same. So a backend Relay can see is healthy beats it, and
     * one Relay can see is struggling loses to it. Both directions matter: excluding it
     * would strand a perfectly fine server that simply has no plugin.
     */
    @Test
    void aSilentServerWithPlayersSitsBetweenHealthyAndStruggling() {
        RegisteredServer healthyOne = server("survival-01");
        RegisteredServer silent = server("survival-02");
        RegisteredServer struggling = server("survival-03");
        healthyOne.setStats(healthy());
        struggling.setStats(stats(15.0, 65.0, 0.5));
        fill(healthyOne, 50);
        fill(silent, 50);
        fill(struggling, 50);

        assertEquals(List.of(healthyOne, silent, struggling),
                new ServerGroup("survival", List.of(struggling, silent, healthyOne))
                        .ordered(BalanceStrategy.RESOURCE_AWARE));
    }

    /**
     * A report old enough to be history is not read as fact.
     *
     * <p>A backend that locked up two minutes ago still holds the healthy figures it had at
     * the time. Without the freshness check it would keep winning every join precisely
     * because it stopped being able to say otherwise.
     */
    @Test
    void aStaleReportIsNotTrusted() {
        RegisteredServer frozen = server("survival-01");
        RegisteredServer healthyOne = server("survival-02");
        long longAgo = System.currentTimeMillis() - BackendStats.FRESH_FOR_MILLIS - 1000;
        frozen.setStats(new BackendStats(20.0, 20.0, 20.0, 5.0, 1L, 4L, 0.1, 0, 60,
                "1.20.2", longAgo));
        healthyOne.setStats(healthy());
        fill(frozen, 10);
        fill(healthyOne, 40);

        assertEquals(List.of(healthyOne, frozen),
                new ServerGroup("survival", List.of(frozen, healthyOne))
                        .ordered(BalanceStrategy.RESOURCE_AWARE),
                "the frozen server's last report says 20 TPS at 5ms; it is two minutes old");
    }

    /** An unavailable member still goes last, whatever its load says. */
    @Test
    void healthStillWinsOverLoad() {
        RegisteredServer idleButUnhealthy = server("survival-01");
        RegisteredServer loadedButUp = server("survival-02");
        idleButUnhealthy.setStats(healthy());
        idleButUnhealthy.setHealth(idleButUnhealthy.health()
                .withState(dev.relay.health.BackendHealth.State.UNHEALTHY));
        loadedButUp.setStats(stats(19.0, 30.0, 0.8));
        fill(loadedButUp, 60);

        assertEquals(List.of(loadedButUp, idleButUnhealthy),
                new ServerGroup("survival", List.of(idleButUnhealthy, loadedButUp))
                        .ordered(BalanceStrategy.RESOURCE_AWARE),
                "an idle server nobody can reach is not a destination");
    }

    // ------------------------------------------------------------------- weighted

    @Test
    void weightedPrefersTheBiggerMachineUntilItHoldsItsShare() {
        RegisteredServer big = weighted("survival-01", 100);
        RegisteredServer small = weighted("survival-02", 50);
        fill(big, 30);
        fill(small, 20);

        // 30/100 = 0.3 against 20/50 = 0.4.
        assertEquals(List.of(big, small),
                new ServerGroup("survival", List.of(big, small)).ordered(BalanceStrategy.WEIGHTED));

        fill(big, 30);                       // 60 now: 0.6 against 0.4
        assertEquals(List.of(small, big),
                new ServerGroup("survival", List.of(big, small)).ordered(BalanceStrategy.WEIGHTED));
    }

    /** Only ratios matter, so two ways of writing the same split behave identically. */
    @Test
    void onlyTheRatioOfWeightsMatters() {
        RegisteredServer hundred = weighted("a-01", 100);
        RegisteredServer fifty = weighted("a-02", 50);
        RegisteredServer two = weighted("b-01", 2);
        RegisteredServer one = weighted("b-02", 1);
        for (RegisteredServer server : List.of(hundred, fifty, two, one)) {
            fill(server, 10);
        }

        assertEquals(
                List.of(hundred, fifty),
                new ServerGroup("a", List.of(hundred, fifty)).ordered(BalanceStrategy.WEIGHTED));
        assertEquals(
                List.of(two, one),
                new ServerGroup("b", List.of(two, one)).ordered(BalanceStrategy.WEIGHTED));
    }

    /** An unweighted backend is weight 1, so mixing the two forms is not a trap. */
    @Test
    void anUnweightedBackendCountsAsOne() {
        RegisteredServer plain = server("survival-01");
        RegisteredServer triple = weighted("survival-02", 3);
        fill(plain, 4);
        fill(triple, 6);

        // 4/1 = 4 against 6/3 = 2.
        assertEquals(List.of(triple, plain),
                new ServerGroup("survival", List.of(plain, triple))
                        .ordered(BalanceStrategy.WEIGHTED));
    }

    @Test
    void bothNewStrategyNamesParse() {
        assertEquals(BalanceStrategy.RESOURCE_AWARE, BalanceStrategy.parse("resource-aware"));
        assertEquals(BalanceStrategy.RESOURCE_AWARE, BalanceStrategy.parse("RESOURCE_AWARE"));
        assertEquals(BalanceStrategy.WEIGHTED, BalanceStrategy.parse("weighted"));
    }

    // ------------------------------------------------------------------- helpers

    private static RegisteredServer server(String name) {
        return new RegisteredServer(new ServerEntry(name, new InetSocketAddress("127.0.0.1", 25565)));
    }

    private static RegisteredServer weighted(String name, double weight) {
        return new RegisteredServer(
                new ServerEntry(name, new InetSocketAddress("127.0.0.1", 25565), weight));
    }

    private static void fill(RegisteredServer server, int count) {
        List<ConnectedPlayer> players = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            players.add(player(server.name() + "-" + i));
        }
        for (ConnectedPlayer player : players) {
            new ServerConnection(server, player).markEstablished();
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
