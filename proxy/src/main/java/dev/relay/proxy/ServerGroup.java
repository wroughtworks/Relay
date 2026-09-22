package dev.relay.proxy;

import dev.relay.config.BalanceStrategy;
import dev.relay.health.BackendStats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Several interchangeable backends under one name.
 *
 * <p>A network with {@code survival-01} and {@code survival-02} wants players to type
 * {@code /server survival} and land on whichever is the better host right now. A group is
 * that name, plus the rule for choosing.
 *
 * <p>{@link #ordered} returns every member rather than a single pick, deliberately. The
 * chosen member goes first and the rest follow, so a group behaves as one destination for
 * balancing and as a fallback list the moment a member refuses or is unreachable. There is
 * no separate failover path that could disagree with the balancing one.
 */
public final class ServerGroup {

    private final String name;
    private final List<RegisteredServer> members;

    /** Round-robin position. Wraps on overflow, which the modulo below tolerates. */
    private final AtomicInteger turn = new AtomicInteger();

    public ServerGroup(String name, List<RegisteredServer> members) {
        if (members.isEmpty()) {
            throw new IllegalArgumentException("Group '" + name + "' has no members");
        }
        this.name = name;
        this.members = List.copyOf(members);
    }

    public String name() {
        return name;
    }

    public List<RegisteredServer> members() {
        return members;
    }

    public int playerCount() {
        int total = 0;
        for (RegisteredServer member : members) {
            total += member.playerCount();
        }
        return total;
    }

    /**
     * The members, best first.
     *
     * <p>Ties keep configured order in every strategy that has ties, so an unbalanced
     * network with nobody online still fills predictably rather than arbitrarily.
     */
    public List<RegisteredServer> ordered(BalanceStrategy strategy) {
        if (members.size() == 1) {
            return members;
        }
        List<RegisteredServer> ordered = new ArrayList<>(members);
        switch (strategy) {
            case LEAST_PLAYERS ->
                // A stable sort, so equal counts stay in configured order.
                    ordered.sort(Comparator.comparingInt(RegisteredServer::playerCount));
            case ROUND_ROBIN -> {
                int start = Math.floorMod(turn.getAndIncrement(), ordered.size());
                Collections.rotate(ordered, -start);
            }
            case RANDOM -> Collections.shuffle(ordered, ThreadLocalRandom.current());
            case WEIGHTED ->
                // Players per unit of weight, so a member given twice the weight is
                // preferred until it holds twice as many.
                    ordered.sort(Comparator.comparingDouble(
                            server -> server.playerCount() / server.weight()));
            case RESOURCE_AWARE -> ordered.sort(
                    // Banded load first, then the player count within a band, which is
                    // what makes this least-players until something is actually strained.
                    Comparator.comparingInt(ServerGroup::loadBand)
                            .thenComparingInt(RegisteredServer::playerCount));
            case FIRST_AVAILABLE -> {
                // Configured order is the answer.
            }
        }

        // Health is applied last, and the sort is stable, so it partitions the strategy's
        // answer rather than replacing it: usable members keep the order the strategy
        // chose, and failing ones keep theirs behind them. Doing this first would have
        // been silently useless, since the strategy sort would sort right over it.
        //
        // Demoted rather than removed, per spec 6.4. A network whose health checks are
        // themselves broken then degrades to the old behaviour of trying anyway, instead
        // of refusing every join.
        ordered.sort(Comparator.comparing(server -> !server.acceptsNewPlayers()));
        return ordered;
    }

    // ------------------------------------------------------- resource-aware load

    /**
     * How loaded a backend is, in tenths, lower being better.
     *
     * <p>Banded rather than continuous, and that is the design rather than a shortcut. A
     * raw score is never equal between two real servers &mdash; tick times wobble by a
     * millisecond constantly &mdash; so a continuous comparison would let noise decide
     * every join, and an empty backend a hair above its sibling would never be filled.
     * Rounding to a tenth means loads that close count as the same, the player count
     * decides between them, and <b>resource-aware behaves exactly like least-players until
     * something is meaningfully more loaded than its siblings</b>. That is the property
     * worth having: a strategy an operator cannot predict is worse than a simple one.
     */
    static int loadBand(RegisteredServer server) {
        return (int) Math.round(load(server) * 10);
    }

    /**
     * 0 for a server with room, rising past 1 for one in trouble.
     *
     * <p>The worst of its measures, not their average. A backend in difficulty on any one
     * axis is in difficulty, and averaging lets a comfortable heap hide a tick rate that
     * is falling over.
     *
     * <p>Each measure is flat until the point the dashboard already calls warm, and
     * reaches 1 where the dashboard calls it hot. Sharing those thresholds is deliberate:
     * the colour an operator sees and the decision the balancer makes then come from the
     * same numbers, so the page explains the routing instead of merely sitting beside it.
     *
     * <p>CPU is deliberately unused, though the backend reports it and spec &sect;6.2
     * lists it. Mean tick time already measures what CPU pressure does to a Minecraft
     * server, more directly and with a threshold that means something; adding CPU would
     * be a second knob carrying the same information and an invented number to compare it
     * against.
     */
    private static double load(RegisteredServer server) {
        BackendStats stats = server.stats();
        if (stats == null || !stats.isFresh()) {
            // A backend can only report while a player is on it, so an empty one being
            // silent is not ignorance -- it is the emptiness itself, which is the best
            // news there is. Treating it as unknown would be a trap: resource-aware would
            // never send anyone to an empty server, so it would never start reporting.
            return server.playerCount() == 0 ? 0 : UNKNOWN;
        }

        double worst = 0;
        // 19.5 and 18 TPS, matching the dashboard's own warm and hot.
        if (stats.tps1m() >= 0) {
            worst = Math.max(worst, (19.5 - stats.tps1m()) / 1.5);
        }
        // 25ms and 50ms per tick. The honest companion to TPS, which saturates at 20 and
        // hides a server doing 19.9 at 45ms.
        if (stats.msptMean() >= 0) {
            worst = Math.max(worst, (stats.msptMean() - 25) / 25);
        }
        // 75% and 90% of heap. Below the knee a JVM is simply using the memory it was
        // given, and reading that as load would prefer whichever server had been restarted
        // most recently.
        if (stats.maxMemory() > 0 && stats.usedMemory() >= 0) {
            double used = (double) stats.usedMemory() / stats.maxMemory();
            worst = Math.max(worst, (used - 0.75) / 0.15);
        }
        return Math.max(0, worst);
    }

    /**
     * What to assume about a backend that has players but is not reporting.
     *
     * <p>Either the Relay plugin is not installed or the server has stopped ticking, and
     * from the outside those look the same. Sitting between warm and hot is the answer
     * that behaves correctly in both directions: a backend Relay can see is healthy wins,
     * and one it can see is struggling loses. Neither preferring nor excluding the silent
     * one is the whole point &mdash; it may be perfectly fine.
     */
    private static final double UNKNOWN = 0.5;

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder(name).append(" [");
        for (int i = 0; i < members.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(members.get(i).name());
        }
        return text.append(']').toString();
    }
}
