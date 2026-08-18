package dev.relay.health;

/**
 * What Relay currently believes about a backend.
 *
 * <p>Spec &sect;6.3 asks for a health state per backend, and &sect;6.4 for unhealthy ones
 * to leave routing decisions. Until now Relay learned a backend was down by trying to put
 * a player on it and watching that fail &mdash; the player pays for the discovery, and a
 * server that has been dead for an hour is still first in the try order.
 *
 * <p>Immutable, and replaced wholesale on each check. A dashboard reading one of these
 * gets a consistent picture rather than fields that disagree because they were sampled
 * mid-update.
 *
 * @param state          the current verdict
 * @param latencyMillis  round trip of the last successful status ping, or -1
 * @param reportedPlayers what the backend itself says is online, or -1 if it has not said.
 *                        Distinct from Relay's own count: a mismatch means players are
 *                        reaching that backend without passing through this proxy
 * @param reportedMax     the backend's own player cap, or -1
 * @param version        the backend's reported version string, or null
 * @param checkedAt      when this was last determined, in epoch millis
 * @param failures       consecutive failed checks, for the hysteresis that stops one
 *                       dropped packet from emptying a server
 * @param detail         why, when something is wrong; null when healthy
 */
public record BackendHealth(State state, long latencyMillis, int reportedPlayers, int reportedMax,
                            String version, long checkedAt, int failures, String detail) {

    /** Spec &sect;6.3's states, less the ones that belong to a v2+ cluster. */
    public enum State {
        /** Answering, and nothing suggests otherwise. */
        HEALTHY,
        /** Answering, but slowly or with something worth noticing. */
        DEGRADED,
        /** Deliberately emptied by an operator. Reachable, but no new players. */
        DRAINING,
        /** Failing checks. */
        UNHEALTHY,
        /** Not answering at all. */
        OFFLINE,
        /** Not checked yet, or checking is switched off. */
        UNKNOWN;

        /**
         * Whether a backend in this state should receive players who are not already on it.
         *
         * <p>{@code UNKNOWN} counts as usable on purpose. It is what every backend looks
         * like in the second before the first check completes, and treating it as unusable
         * would refuse every join in that window.
         */
        public boolean acceptsNewPlayers() {
            return this == HEALTHY || this == DEGRADED || this == UNKNOWN;
        }
    }

    public static BackendHealth unknown() {
        return new BackendHealth(State.UNKNOWN, -1, -1, -1, null, 0, 0, null);
    }

    public boolean acceptsNewPlayers() {
        return state.acceptsNewPlayers();
    }

    /** The same health, in a state an operator set by hand. */
    public BackendHealth withState(State replacement) {
        return new BackendHealth(replacement, latencyMillis, reportedPlayers, reportedMax,
                version, checkedAt, failures, detail);
    }
}
