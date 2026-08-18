package dev.relay.api.backend;

/**
 * Constants for the backend-facing plugin-message API.
 *
 * <p>A plugin on a backend server talks to the proxy by sending a plugin message to one
 * of its own players; the proxy intercepts it on the way past rather than letting it
 * reach the client. Replies travel the same way in reverse. This is how BungeeCord has
 * always worked, and Relay is deliberately wire-compatible with it: a plugin written for
 * BungeeCord or Velocity needs no changes.
 *
 * <h2>Trust</h2>
 * A backend is trusted infrastructure &mdash; it already holds the forwarding secret and
 * sits behind the same firewall &mdash; so these requests are not permission-checked the
 * way the {@link dev.relay.api.ClientApi client API} checks a modded client's. A backend
 * asking to move a player simply moves them. That is the same trust BungeeCord extends,
 * and it is why the API channel must never be reachable from a client: see
 * {@link BackendApiHandler} for how messages arriving from the wrong direction are
 * rejected.
 *
 * <h2>Encoding</h2>
 * The payload is Java's {@code DataInputStream} format &mdash; {@code writeUTF} strings
 * and big-endian primitives &mdash; not Minecraft's VarInt encoding. That is what
 * BungeeCord chose, and matching it is the whole point.
 */
public final class BackendApi {

    /** The modern BungeeCord channel, and what current plugins use. */
    public static final String BUNGEE_CHANNEL = "bungeecord:main";

    /**
     * The pre-1.13 channel name. Still emitted by older plugins, and cheap to accept.
     */
    public static final String LEGACY_BUNGEE_CHANNEL = "BungeeCord";

    /** Relay's own channel, for behaviour BungeeCord never had. */
    public static final String RELAY_CHANNEL = "relay:main";

    /** Bound on a forwarded payload, matching the plugin-message frame limit. */
    public static final int MAX_FORWARD_BYTES = 32 * 1024;

    private BackendApi() {
    }

    public static boolean isApiChannel(String channel) {
        return channel.equals(BUNGEE_CHANNEL)
                || channel.equals(LEGACY_BUNGEE_CHANNEL)
                || channel.equals(RELAY_CHANNEL);
    }

    /** Sub-channels, named exactly as BungeeCord names them. */
    public static final class Sub {
        /** Move the sending player to another server. */
        public static final String CONNECT = "Connect";
        /** Move a named player to another server. */
        public static final String CONNECT_OTHER = "ConnectOther";
        /** Ask which server the sending player is on. */
        public static final String GET_SERVER = "GetServer";
        /** Ask for every configured server name. */
        public static final String GET_SERVERS = "GetServers";
        /** Ask how many players are on a server, or {@code ALL}. */
        public static final String PLAYER_COUNT = "PlayerCount";
        /** Ask who is on a server, or {@code ALL}. */
        public static final String PLAYER_LIST = "PlayerList";
        /** Send a chat message to a player, as JSON. */
        public static final String MESSAGE = "Message";
        /** As {@link #MESSAGE}, kept separate because BungeeCord distinguishes them. */
        public static final String MESSAGE_RAW = "MessageRaw";
        /** Disconnect a player with a reason. */
        public static final String KICK_PLAYER = "KickPlayer";
        /** Ask for the sending player's address. */
        public static final String IP = "IP";
        /** Ask for the sending player's UUID. */
        public static final String UUID = "UUID";
        /** Ask for a named player's UUID. */
        public static final String UUID_OTHER = "UUIDOther";
        /** Relay an opaque payload to another server, or {@code ALL}. */
        public static final String FORWARD = "Forward";
        /** Relay an opaque payload to whichever server a named player is on. */
        public static final String FORWARD_TO_PLAYER = "ForwardToPlayer";

        private Sub() {
        }
    }
}
