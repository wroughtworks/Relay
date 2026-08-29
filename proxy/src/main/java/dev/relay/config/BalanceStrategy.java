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
