package dev.relay.proxy;

import dev.relay.config.BalanceStrategy;

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
            case FIRST_AVAILABLE -> {
                // Configured order is the answer.
            }
        }
        return ordered;
    }

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
