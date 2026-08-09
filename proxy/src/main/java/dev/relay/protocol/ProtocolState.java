package dev.relay.protocol;

/** The connection state a Minecraft channel is currently in. */
public enum ProtocolState {
    HANDSHAKE,
    STATUS,
    LOGIN,
    CONFIGURATION,
    PLAY;

    /** The state id carried in the handshake packet's "next state" field. */
    public static ProtocolState fromHandshakeId(int id) {
        return switch (id) {
            case 1 -> STATUS;
            case 2 -> LOGIN;
            // 3 is the 1.20.5+ "transfer" intent; it follows the same path as login.
            case 3 -> LOGIN;
            default -> null;
        };
    }
}
