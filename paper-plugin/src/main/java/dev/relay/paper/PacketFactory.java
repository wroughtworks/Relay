package dev.relay.paper;

import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Typed messages between backends, routed by the proxy.
 *
 * <p>Relay has always been able to carry bytes from one server to another &mdash;
 * {@code Forward} takes a server name and an opaque payload, exactly as BungeeCord's does.
 * What that leaves every plugin to invent is the part above it: deciding what the bytes
 * mean, keeping the two ends agreeing on it, and getting a message to the code that
 * handles it. This is that part, written once.
 *
 * <p>Both servers build the same factory from the same code:
 *
 * <pre>{@code
 * PacketFactory packets = PacketFactory.named("parties")
 *         .register(PartyInvite.class, PartyInvite::new)
 *         .register(PartyDisband.class, PartyDisband::new)
 *         .build(this);
 *
 * packets.on(PartyInvite.class, (from, invite) ->
 *         getLogger().info(invite.to + " was invited, from " + from));
 *
 * packets.send("survival", new PartyInvite(player.getUniqueId(), "Notch"));
 * }</pre>
 *
 * <h2>Ids, and the thing that makes them safe</h2>
 * A packet's id is its position in the registration list. That is what makes the sending
 * side and the receiving side agree without anyone writing a number down &mdash; and it is
 * also the obvious way to get it catastrophically wrong, because two servers that register
 * in different orders will each decode the other's packets into the wrong class and read
 * one field's bytes as another's.
 *
 * <p>So every message carries a fingerprint of the registry that produced it: the factory
 * name and every registered class name, in order. A packet whose fingerprint does not match
 * the receiver's is dropped and reported, once per mismatching registry. The failure that
 * would otherwise be silent and confusing becomes a line in the log naming both sides.
 *
 * <h2>Addressing</h2>
 * {@link #send} takes a server name or a group name &mdash; a group reaches every member,
 * because which member a given player is on is the proxy's business rather than the sending
 * plugin's. {@link #sendToPlayer} follows one person wherever they are, and
 * {@link #broadcast} reaches every server with somebody on it.
 *
 * <h2>The one real limitation</h2>
 * A plugin message travels on a player's connection, so a server with nobody on it cannot
 * be reached and cannot send. This is inherited from how plugin messaging works and
 * BungeeCord has it too. Sending to an empty server returns {@code false} rather than
 * failing silently; sending from an empty server holds the packet until somebody joins,
 * because "nobody is here yet" is usually a startup ordering problem rather than a reason
 * to lose the message.
 */
public final class PacketFactory {

    /** Bumped if the envelope below ever changes shape. */
    static final byte FORMAT = 1;

    /** Sub-channel prefix, so two factories on one server never read each other's mail. */
    static final String SUB_CHANNEL_PREFIX = "RelayPacket:";

    /**
     * How many packets to hold while waiting to learn this server's own name.
     *
     * <p>Enough to cover the gap between a plugin starting and the first player arriving,
     * and small enough that a server which never gets one does not accumulate a queue it
     * will never send.
     */
    static final int MAX_HELD = 64;

    /**
     * The largest a packet may be, matching {@code BackendApi.MAX_FORWARD_BYTES}.
     *
     * <p>Checked here rather than left to the proxy because the length travels as an
     * unsigned short: a payload over 65535 bytes would not be refused on arrival, it would
     * be read with a wrapped length and take the rest of the stream with it. Better to say
     * so in the log of the server that wrote it, next to the packet's name.
     */
    static final int MAX_BYTES = 32 * 1024;

    private final String name;
    private final String subChannel;
    private final int fingerprint;
    private final List<Class<? extends RelayPacket>> byId;
    private final List<Supplier<? extends RelayPacket>> blanks;
    private final Map<Class<? extends RelayPacket>, Integer> ids;
    private final PacketCarrier carrier;
    private final Reporter reporter;

    private final Map<Class<?>, PacketHandler<?>> handlers = new ConcurrentHashMap<>();
    private final Deque<Held> held = new ArrayDeque<>();
    private final Set<Integer> complainedAbout = ConcurrentHashMap.newKeySet();

    private PacketFactory(String name, List<Class<? extends RelayPacket>> byId,
                          List<Supplier<? extends RelayPacket>> blanks,
                          PacketCarrier carrier, Reporter reporter) {
        this.name = name;
        this.subChannel = SUB_CHANNEL_PREFIX + name;
        this.byId = List.copyOf(byId);
        this.blanks = List.copyOf(blanks);
        this.carrier = carrier;
        this.reporter = reporter;
        Map<Class<? extends RelayPacket>, Integer> lookup = new HashMap<>();
        for (int id = 0; id < this.byId.size(); id++) {
            lookup.put(this.byId.get(id), id);
        }
        this.ids = Map.copyOf(lookup);
        this.fingerprint = fingerprint(name, this.byId);
    }

    /** Starts a factory. The name namespaces it, so two plugins never collide. */
    public static Builder named(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a factory needs a name; it is what keeps two "
                    + "plugins' packets apart on a shared channel");
        }
        return new Builder(name);
    }

    /** Collects the packet types, in the order that gives them their ids. */
    public static final class Builder {

        private final String name;
        private final Map<Class<? extends RelayPacket>, Supplier<? extends RelayPacket>> types =
                new LinkedHashMap<>();

        private Builder(String name) {
            this.name = name;
        }

        /**
         * Adds a packet type. Its id is how many were registered before it.
         *
         * @param blank makes an empty instance for the receiving side to read into
         */
        public <T extends RelayPacket> Builder register(Class<T> type, Supplier<T> blank) {
            if (types.putIfAbsent(type, blank) != null) {
                throw new IllegalArgumentException(type.getName()
                        + " is registered twice, which would give it two ids");
            }
            return this;
        }

        /** Builds the factory and wires it to the proxy through the plugin's channels. */
        public PacketFactory build(Plugin plugin) {
            PluginCarrier carrier = new PluginCarrier(plugin);
            PacketFactory factory = build(carrier, new PluginReporter(plugin));
            carrier.listenFor(factory);
            return factory;
        }

        /** The seam a test drives: everything above this line is Bukkit, everything below is not. */
        PacketFactory build(PacketCarrier carrier, Reporter reporter) {
            if (types.isEmpty()) {
                throw new IllegalStateException("a factory with no packets registered can "
                        + "neither send nor receive anything");
            }
            return new PacketFactory(name, new ArrayList<>(types.keySet()),
                    new ArrayList<>(types.values()), carrier, reporter);
        }
    }

    // ------------------------------------------------------------------ what it is

    public String name() {
        return name;
    }

    /** The sub-channel this factory's packets travel under. */
    public String subChannel() {
        return subChannel;
    }

    /**
     * The registry's identity, which both ends must agree on.
     *
     * <p>Worth logging on startup: two servers printing different numbers here is the
     * whole explanation for packets that vanish.
     */
    public int fingerprint() {
        return fingerprint;
    }

    /** The id this factory gave a packet type. */
    public int idOf(Class<? extends RelayPacket> type) {
        Integer id = ids.get(type);
        if (id == null) {
            throw new IllegalArgumentException(type.getName() + " was never registered with the '"
                    + name + "' factory, so it has no id to travel under");
        }
        return id;
    }

    /** Registers what to do when one of these arrives. One handler per type; the last wins. */
    public <T extends RelayPacket> PacketFactory on(Class<T> type, PacketHandler<T> handler) {
        idOf(type);                     // refuses a handler for a packet that cannot arrive
        handlers.put(type, handler);
        return this;
    }

    // ------------------------------------------------------------------ sending

    /**
     * Sends to a backend, or to every member of a group.
     *
     * <p>A group that contains this server includes it: the packet comes back round and
     * arrives here too. That is usually what "tell the survival pool" should mean, but it
     * is worth knowing before writing a handler that acts on it.
     *
     * <p><b>What the return value means.</b> False is "this server could not send it" --
     * nobody is online here to carry it, the packet would not encode, or it was too big.
     * True is not a delivery receipt. The proxy decides where it goes after this call
     * returns, and a name that matches no backend, or one with nobody on it, is dropped
     * there with nothing to report back; the proxy logs that at debug. Plugin messaging
     * has no acknowledgement to build one out of, so a protocol that needs to know its
     * message arrived has to say so with a packet of its own.
     */
    public boolean send(String server, RelayPacket packet) {
        return send(Destination.SERVER, server, packet);
    }

    /** Sends to whichever server a named player is on. @see #send for what false means */
    public boolean sendToPlayer(String player, RelayPacket packet) {
        return send(Destination.PLAYER, player, packet);
    }

    /** Sends to every server with somebody on it, this one excepted. */
    public boolean broadcast(RelayPacket packet) {
        return send(Destination.SERVER, "ALL", packet);
    }

    private boolean send(Destination to, String target, RelayPacket packet) {
        int id = idOf(packet.getClass());
        String me = carrier.serverName();
        if (me == null) {
            // The proxy has not told us who we are yet, and it cannot until a player is
            // here to ask through -- which is the same condition sending needs anyway.
            return hold(to, target, packet);
        }
        byte[] payload;
        try {
            payload = encode(me, id, packet);
        } catch (RuntimeException badPacket) {
            reporter.report("Could not write " + packet.getClass().getSimpleName()
                    + " for '" + name + "'; it was not sent", badPacket);
            return false;
        }
        if (payload.length > MAX_BYTES) {
            reporter.report(packet.getClass().getSimpleName() + " came to " + payload.length
                    + " bytes, over the " + MAX_BYTES + " a forwarded plugin message may carry; "
                    + "it was not sent. Send a reference and let the far side fetch the rest.");
            return false;
        }
        return to == Destination.PLAYER
                ? carrier.forwardToPlayer(target, subChannel, payload)
                : carrier.forward(target, subChannel, payload);
    }

    private boolean hold(Destination to, String target, RelayPacket packet) {
        synchronized (held) {
            if (held.size() >= MAX_HELD) {
                reporter.report("Dropping " + packet.getClass().getSimpleName() + ": '" + name
                        + "' is holding " + MAX_HELD + " packets waiting for a player to send "
                        + "through, and this server has had none");
                return false;
            }
            held.add(new Held(to, target, packet));
        }
        return true;
    }

    /**
     * Sends everything that was waiting to learn this server's name.
     *
     * <p>Drained before resending so a packet that still cannot go anywhere is refused
     * rather than queued forever against itself.
     */
    void flushHeld() {
        List<Held> waiting;
        synchronized (held) {
            if (held.isEmpty()) {
                return;
            }
            waiting = new ArrayList<>(held);
            held.clear();
        }
        for (Held one : waiting) {
            send(one.to, one.target, one.packet);
        }
    }

    private byte[] encode(String from, int id, RelayPacket packet) {
        PacketBuffer out = PacketBuffer.writing();
        out.writeByte(FORMAT);
        out.writeInt(fingerprint);
        out.writeVarInt(id);
        out.writeString(from);
        packet.write(out);
        return out.written();
    }

    // ------------------------------------------------------------------ receiving

    /**
     * Decodes one payload and hands it to its handler.
     *
     * <p>Every way this can go wrong ends in a report and a dropped packet, never an
     * exception escaping into the caller's plugin-message listener. A malformed message
     * should cost the message.
     */
    void receive(byte[] payload) {
        PacketBuffer in = PacketBuffer.reading(payload, 0, payload.length);
        int id;
        String from;
        RelayPacket packet;
        try {
            byte format = in.readByte();
            if (format != FORMAT) {
                complainOnce(format, "Ignoring a '" + name + "' packet in format " + format
                        + "; this server speaks " + FORMAT + ". The two are running different "
                        + "versions of the Relay plugin.");
                return;
            }
            int theirs = in.readInt();
            if (theirs != fingerprint) {
                complainOnce(theirs, "Ignoring a '" + name + "' packet from a registry "
                        + "fingerprinted " + theirs + "; this one is " + fingerprint + ". The two "
                        + "servers have registered different packets, or registered them in a "
                        + "different order, and decoding it would read one packet as another. "
                        + "Here it is " + describe() + ".");
                return;
            }
            id = in.readVarInt();
            from = in.readString();
            if (id < 0 || id >= byId.size()) {
                // Not reachable while the fingerprints agree, which is the point of them.
                reporter.report("Ignoring '" + name + "' packet id " + id
                        + ", which is outside a registry of " + byId.size());
                return;
            }
            packet = blanks.get(id).get();
            packet.read(in);
        } catch (RuntimeException malformed) {
            reporter.report("Could not read a '" + name + "' packet; dropping it", malformed);
            return;
        }
        if (in.remaining() > 0) {
            // Nothing is broken yet, but the two sides disagree about this packet's shape
            // and the next field added on either side is where it starts costing data.
            reporter.report(packet.getClass().getSimpleName() + " left " + in.remaining()
                    + " bytes unread; its write and read do not agree");
        }
        deliver(from, packet);
    }

    @SuppressWarnings("unchecked")
    private void deliver(String from, RelayPacket packet) {
        PacketHandler<RelayPacket> handler =
                (PacketHandler<RelayPacket>) handlers.get(packet.getClass());
        if (handler == null) {
            reporter.detail("No handler for " + packet.getClass().getSimpleName()
                    + " on '" + name + "'; it arrived and was discarded");
            return;
        }
        try {
            handler.handle(from, packet);
        } catch (RuntimeException thrown) {
            // A handler is somebody else's code running inside a plugin-message listener.
            // Letting it out would take the channel down for every other packet.
            reporter.report("Handler for " + packet.getClass().getSimpleName()
                    + " on '" + name + "' threw", thrown);
        }
    }

    private void complainOnce(int about, String message) {
        if (complainedAbout.size() < 16 && complainedAbout.add(about)) {
            reporter.report(message);
        }
    }

    private String describe() {
        StringBuilder out = new StringBuilder();
        for (int id = 0; id < byId.size(); id++) {
            out.append(id == 0 ? "" : ", ").append(id).append('=')
                    .append(byId.get(id).getSimpleName());
        }
        return out.toString();
    }

    /**
     * A stable hash of what this factory can carry.
     *
     * <p>Built from {@link String#hashCode}, whose result is specified by the language
     * rather than left to the implementation -- so two servers on two different JVMs get
     * the same number for the same registry, which is the entire point.
     */
    private static int fingerprint(String name, List<Class<? extends RelayPacket>> types) {
        int hash = name.hashCode();
        for (Class<?> type : types) {
            hash = hash * 31 + type.getName().hashCode();
        }
        return hash;
    }

    private enum Destination { SERVER, PLAYER }

    private record Held(Destination to, String target, RelayPacket packet) {
    }
}
