package dev.relay.paper;

/**
 * What to do with a packet that arrived from another server.
 *
 * @param <T> the packet type this handler was registered for
 */
@FunctionalInterface
public interface PacketHandler<T extends RelayPacket> {

    /**
     * @param from   the backend that sent it, as the proxy names it. Empty only if the
     *               sender had not yet learned its own name, which it asks the proxy for
     *               the moment it has a player to ask through
     * @param packet the decoded packet
     */
    void handle(String from, T packet);
}
