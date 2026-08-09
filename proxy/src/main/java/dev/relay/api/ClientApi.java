package dev.relay.api;

/**
 * Constants for Relay's client-facing plugin-message protocol.
 *
 * <p>A modded client and the proxy talk over a single custom-payload channel while the
 * player is in configuration or play state. The design has three properties that matter:
 *
 * <ul>
 *   <li><b>Invisible to vanilla.</b> A vanilla client never sends on this channel and
 *       Relay never sends unsolicited, so an unmodded player is unaffected. Everything
 *       begins with the client saying hello.</li>
 *   <li><b>Version negotiated.</b> Both sides state a protocol version and the lower one
 *       is used, so a mod and a proxy can be upgraded independently.</li>
 *   <li><b>Never trusted.</b> The client is told which capabilities it may use, but every
 *       request is re-checked against the same permission nodes that guard the in-game
 *       commands. A modded client is an attacker-controlled peer, not a privileged
 *       one.</li>
 * </ul>
 *
 * <p>The channel is claimed by the proxy and never relayed to a backend: a backend has no
 * idea what these messages mean, and forwarding them would let a client reach backend
 * plugins through a channel they did not opt into.
 */
public final class ClientApi {

    /**
     * The channel both sides use. Versioned in the name so an incompatible future
     * revision can coexist rather than having to interpret an old message as a new one.
     */
    public static final String CHANNEL = "relay:api_v1";

    /** The newest protocol revision this proxy speaks. */
    public static final int PROTOCOL_VERSION = 1;

    /**
     * Rejects a client that talks before saying hello, or floods the channel. The API is
     * request/response at human speed &mdash; opening a menu, clicking a server &mdash;
     * so a low ceiling costs nothing and bounds what a hostile mod can do.
     */
    public static final int MAX_MESSAGES_PER_SECOND = 20;

    /** Anything larger is refused unread. Requests here are tens of bytes. */
    public static final int MAX_MESSAGE_BYTES = 32 * 1024;

    private ClientApi() {
    }

    /** Client &rarr; proxy message types. */
    public static final class Serverbound {
        /** First message; carries the client's protocol version and mod identity. */
        public static final int HELLO = 0x00;
        /** Asks for the backend list the player is allowed to see. */
        public static final int LIST_SERVERS = 0x01;
        /** Asks to be moved to a named backend. */
        public static final int REQUEST_SWITCH = 0x02;

        private Serverbound() {
        }
    }

    /** Proxy &rarr; client message types. */
    public static final class Clientbound {
        /** Answer to {@link Serverbound#HELLO}: negotiated version and permitted capabilities. */
        public static final int WELCOME = 0x00;
        /** The backend list, with live player counts. */
        public static final int SERVER_LIST = 0x01;
        /** Progress of a switch the client asked for, or that anything else triggered. */
        public static final int SWITCH_STATUS = 0x02;
        /** A request was refused, with a reason the mod can show. */
        public static final int ERROR = 0x03;

        private Clientbound() {
        }
    }

    /**
     * Capability names advertised in the welcome message.
     *
     * <p>Each maps to a permission node, so what a client is told it can do already
     * reflects that player's permissions &mdash; a mod can grey out a button rather than
     * offering an action that will be refused.
     */
    public static final class Capability {
        public static final String SERVER_LIST = "server_list";
        public static final String SWITCH = "switch";

        private Capability() {
        }
    }

    /** Refusal reasons, kept stable so a mod can localise them. */
    public static final class Error {
        public static final int NO_PERMISSION = 0x00;
        public static final int UNKNOWN_SERVER = 0x01;
        public static final int SWITCH_IN_PROGRESS = 0x02;
        public static final int ALREADY_CONNECTED = 0x03;
        public static final int RATE_LIMITED = 0x04;
        public static final int MALFORMED = 0x05;

        private Error() {
        }
    }
}
