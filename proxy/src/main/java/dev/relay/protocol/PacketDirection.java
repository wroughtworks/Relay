package dev.relay.protocol;

/**
 * Which way a packet travels, named from the Minecraft server's point of view
 * (matching the protocol documentation, not Relay's).
 */
public enum PacketDirection {
    /** Client &rarr; server. Relay reads these from players and writes them to backends. */
    SERVERBOUND,
    /** Server &rarr; client. Relay reads these from backends and writes them to players. */
    CLIENTBOUND;

    public PacketDirection opposite() {
        return this == SERVERBOUND ? CLIENTBOUND : SERVERBOUND;
    }
}
