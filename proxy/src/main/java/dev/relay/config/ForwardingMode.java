package dev.relay.config;

import java.util.Locale;

/** How Relay tells a backend who the player really is. */
public enum ForwardingMode {

    /**
     * No forwarding. The backend sees the proxy's address and must run in offline mode,
     * which also means it cannot tell players apart. Local testing only.
     */
    NONE,

    /**
     * BungeeCord's scheme: profile data appended to the handshake hostname, NUL
     * separated, with no authentication of any kind.
     *
     * <p>Anyone who can reach the backend port can claim to be anyone. Only safe when
     * backends are firewalled to the proxy, which spec &sect;8 assumes but does not
     * enforce. Present for compatibility with backends that cannot do better.
     */
    LEGACY,

    /**
     * Velocity's scheme: the profile is requested by the backend over a login plugin
     * channel and returned with an HMAC-SHA256 signature over a shared secret.
     *
     * <p>The default, and the only mode where a backend can actually verify that a login
     * came from this proxy.
     */
    MODERN;

    public static ForwardingMode parse(String value) {
        return switch (value.toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "none" -> NONE;
            case "legacy", "bungeecord", "bungee" -> LEGACY;
            case "modern", "velocity" -> MODERN;
            default -> throw new IllegalArgumentException(
                    "Unknown forwarding mode '" + value + "'; expected none, legacy or modern");
        };
    }

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
