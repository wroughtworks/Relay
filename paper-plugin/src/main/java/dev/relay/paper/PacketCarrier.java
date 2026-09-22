package dev.relay.paper;

/**
 * What actually moves a factory's bytes, and what it knows about where they come from.
 *
 * <p>An interface for the same reason {@link Reporter} is one: everything interesting
 * about {@link PacketFactory} -- ids, fingerprints, encoding, dispatch, the queue -- is
 * ordinary logic that a test can drive, and all of it would be untestable if the class
 * reached for {@code Bukkit.getOnlinePlayers()} in the middle. The Bukkit half is
 * {@link PluginCarrier} and is deliberately too small to hide a bug.
 */
interface PacketCarrier {

    /**
     * Sends one payload to a backend or every member of a group.
     *
     * @return false if there was nothing to carry it there
     */
    boolean forward(String target, String subChannel, byte[] payload);

    /** Sends one payload to whichever server a named player is on. */
    boolean forwardToPlayer(String player, String subChannel, byte[] payload);

    /**
     * This server's name as the proxy knows it, or null until the proxy has said.
     *
     * <p>Null is the normal state at startup rather than an error: the answer arrives by
     * plugin message, which needs a player to travel on.
     */
    String serverName();
}
