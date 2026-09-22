package dev.relay.config;

import java.util.Locale;

/**
 * How a group picks which of its interchangeable backends a player should go to.
 *
 * <p>The choice only ever produces an <em>order</em>, never a single answer. Every
 * strategy returns the whole group with its preferred member first, so a member that
 * refuses the connection simply falls through to the next one. That keeps balancing and
 * failover the same mechanism rather than two that have to agree.
 */
public enum BalanceStrategy {

    /**
     * Fewest players first. The sensible default: it fills a new or emptied server before
     * a busy one, and it self-corrects, since a server that people leave becomes the
     * preferred destination again without anything having to track history.
     */
    LEAST_PLAYERS("least-players"),

    /**
     * Each request takes the next member in turn. Spreads joins evenly regardless of who
     * is already on, which is what you want when players arrive and leave in waves and
     * player count lags behind the truth.
     */
    ROUND_ROBIN("round-robin"),

    /** Uniformly at random. Cheap, stateless, and even enough over any real number of joins. */
    RANDOM("random"),

    /**
     * Fewest players per unit of configured weight.
     *
     * <p>Spec &sect;6.2's weighted strategy, for a group whose machines are not the same.
     * A backend given twice the weight is preferred until it holds twice the players, so
     * the ratios are what matter and {@code 100}/{@code 50} behaves identically to
     * {@code 2}/{@code 1}.
     *
     * <p>Static, which is the point: it describes hardware rather than reacting to load.
     * Use {@link #RESOURCE_AWARE} when what a backend can take varies with what it is
     * doing.
     */
    WEIGHTED("weighted"),

    /**
     * Least loaded first, by what the backends say about themselves.
     *
     * <p>Spec &sect;6.2. {@link #LEAST_PLAYERS} treats forty players in a quiet lobby and
     * forty in a redstone farm as the same load; this reads the TPS, tick time and heap
     * the Relay plugin already reports, so a server that is struggling stops being handed
     * new arrivals before anyone has to notice and drain it.
     *
     * <p>It degrades to {@code least-players} rather than replacing it: loads close
     * together count as equal, and the player count decides between them. A network where
     * nothing is under strain therefore balances exactly as it did before.
     */
    RESOURCE_AWARE("resource-aware"),

    /**
     * Configured order, always. Not balancing at all: the group becomes a priority list
     * where later members exist only to catch failures of earlier ones.
     */
    FIRST_AVAILABLE("first-available");

    private final String configName;

    BalanceStrategy(String configName) {
        this.configName = configName;
    }

    public String configName() {
        return configName;
    }

    public static BalanceStrategy parse(String value) {
        String normalised = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (BalanceStrategy strategy : values()) {
            if (strategy.configName.equals(normalised)) {
                return strategy;
            }
        }
        StringBuilder known = new StringBuilder();
        for (BalanceStrategy strategy : values()) {
            if (!known.isEmpty()) {
                known.append(", ");
            }
            known.append(strategy.configName);
        }
        throw new IllegalArgumentException("Unknown balance strategy '" + value + "'; expected one of: " + known);
    }
}
