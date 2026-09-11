package dev.relay.paper;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Typed packets between backends.
 *
 * <p>The transport underneath this has always worked: {@code Forward} takes a server name
 * and some bytes. What is new is everything that decides what the bytes mean, and its worst
 * failure is not an exception. Two servers that registered their packets in different
 * orders would each decode the other's messages into the wrong class and read one field's
 * bytes as another's &mdash; a party invite arriving as a shop sync, with plausible-looking
 * garbage in it, and nothing anywhere saying so.
 *
 * <p>So most of these tests are about that: the id a packet travels under, the fingerprint
 * that proves both ends mean the same thing by it, and what happens when they do not.
 */
class PacketFactoryTest {

    // ------------------------------------------------------------ packets under test

    static final class Invite implements RelayPacket {
        UUID from;
        String to;
        int expiresInSeconds;

        Invite() {
        }

        Invite(UUID from, String to, int expiresInSeconds) {
            this.from = from;
            this.to = to;
            this.expiresInSeconds = expiresInSeconds;
        }

        @Override
        public void write(PacketBuffer out) {
            out.writeUuid(from);
            out.writeString(to);
            out.writeVarInt(expiresInSeconds);
        }

        @Override
        public void read(PacketBuffer in) {
            from = in.readUuid();
            to = in.readString();
            expiresInSeconds = in.readVarInt();
        }
    }

    static final class Disband implements RelayPacket {
        String reason;

        @Override
        public void write(PacketBuffer out) {
            out.writeString(reason);
        }

        @Override
        public void read(PacketBuffer in) {
            reason = in.readString();
        }
    }

    /** Registered nowhere, so it can stand in for "a packet this factory never heard of". */
    static final class Stranger implements RelayPacket {
        @Override
        public void write(PacketBuffer out) {
            out.writeString("nowhere to go");
        }

        @Override
        public void read(PacketBuffer in) {
            in.readString();
        }
    }

    /**
     * Writes two fields and reads one, which is what a half-finished version bump looks
     * like: the sender has the new field, the receiver has not been updated yet.
     */
    static final class Chatty implements RelayPacket {
        @Override
        public void write(PacketBuffer out) {
            out.writeString("what both sides know about");
            out.writeString("the field only the sender has");
        }

        @Override
        public void read(PacketBuffer in) {
            in.readString();
        }
    }

    // ------------------------------------------------------------ a carrier and a log

    /** Stands in for the proxy: keeps what was sent so the far side can be handed it. */
    private static final class Wire implements PacketCarrier {
        final List<String> destinations = new ArrayList<>();
        final List<byte[]> payloads = new ArrayList<>();
        String name;
        boolean anyoneOnline = true;

        Wire(String name) {
            this.name = name;
        }

        @Override
        public boolean forward(String target, String subChannel, byte[] payload) {
            return record("server:" + target, payload);
        }

        @Override
        public boolean forwardToPlayer(String player, String subChannel, byte[] payload) {
            return record("player:" + player, payload);
        }

        @Override
        public String serverName() {
            return name;
        }

        private boolean record(String where, byte[] payload) {
            if (!anyoneOnline) {
                return false;
            }
            destinations.add(where);
            payloads.add(payload);
            return true;
        }

        byte[] last() {
            return payloads.get(payloads.size() - 1);
        }
    }

    private static final class Log implements Reporter {
        final List<String> warnings = new ArrayList<>();
        final List<String> details = new ArrayList<>();

        @Override
        public void report(String message) {
            warnings.add(message);
        }

        @Override
        public void report(String message, Throwable cause) {
            warnings.add(message);
        }

        @Override
        public void detail(String message) {
            details.add(message);
        }
    }

    private static PacketFactory.Builder parties() {
        return PacketFactory.named("parties")
                .register(Invite.class, Invite::new)
                .register(Disband.class, Disband::new);
    }

    // ------------------------------------------------------------ ids

    @Test
    void idsAreRegistrationOrder() {
        PacketFactory factory = parties().build(new Wire("lobby"), new Log());

        assertEquals(0, factory.idOf(Invite.class));
        assertEquals(1, factory.idOf(Disband.class));
    }

    @Test
    void bothEndsOfTheSameRegistrationGiveTheSameIds() {
        PacketFactory sender = parties().build(new Wire("lobby"), new Log());
        PacketFactory receiver = parties().build(new Wire("survival"), new Log());

        assertEquals(sender.idOf(Invite.class), receiver.idOf(Invite.class));
        assertEquals(sender.fingerprint(), receiver.fingerprint());
    }

    @Test
    void aPacketThatWasNeverRegisteredHasNoId() {
        PacketFactory factory = parties().build(new Wire("lobby"), new Log());

        assertThrows(IllegalArgumentException.class, () -> factory.idOf(Stranger.class));
        assertThrows(IllegalArgumentException.class,
                () -> factory.on(Stranger.class, (from, packet) -> { }),
                "a handler for a packet that can never arrive is a mistake worth refusing");
    }

    @Test
    void registeringTheSamePacketTwiceIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> PacketFactory.named("x")
                        .register(Invite.class, Invite::new)
                        .register(Invite.class, Invite::new));
    }

    @Test
    void anEmptyFactoryIsRefused() {
        assertThrows(IllegalStateException.class,
                () -> PacketFactory.named("x").build(new Wire("lobby"), new Log()));
    }

    // ------------------------------------------------------------ the round trip

    @Test
    void aPacketArrivesOnTheOtherSideAsWhatWasSent() {
        Wire wire = new Wire("lobby");
        PacketFactory sender = parties().build(wire, new Log());
        PacketFactory receiver = parties().build(new Wire("survival"), new Log());

        List<Invite> got = new ArrayList<>();
        List<String> senders = new ArrayList<>();
        receiver.on(Invite.class, (from, invite) -> {
            senders.add(from);
            got.add(invite);
        });

        UUID who = UUID.randomUUID();
        assertTrue(sender.send("survival", new Invite(who, "Notch", 120)));
        assertEquals(List.of("server:survival"), wire.destinations);

        receiver.receive(wire.last());

        assertEquals(1, got.size());
        assertEquals(who, got.get(0).from);
        assertEquals("Notch", got.get(0).to);
        assertEquals(120, got.get(0).expiresInSeconds);
        assertEquals(List.of("lobby"), senders, "the receiver should know who sent it");
    }

    @Test
    void eachPacketReachesItsOwnHandlerAndNoOther() {
        Wire wire = new Wire("lobby");
        PacketFactory sender = parties().build(wire, new Log());
        PacketFactory receiver = parties().build(new Wire("survival"), new Log());

        List<String> seen = new ArrayList<>();
        receiver.on(Invite.class, (from, p) -> seen.add("invite"));
        receiver.on(Disband.class, (from, p) -> seen.add("disband " + p.reason));

        Disband disband = new Disband();
        disband.reason = "everyone left";
        sender.send("survival", disband);
        receiver.receive(wire.last());
        sender.send("survival", new Invite(UUID.randomUUID(), "Notch", 1));
        receiver.receive(wire.last());

        assertEquals(List.of("disband everyone left", "invite"), seen);
    }

    @Test
    void addressingAPlayerGoesToThePlayerRoute() {
        Wire wire = new Wire("lobby");
        PacketFactory factory = parties().build(wire, new Log());

        factory.sendToPlayer("Notch", new Invite(UUID.randomUUID(), "Notch", 5));
        factory.broadcast(new Invite(UUID.randomUUID(), "Anyone", 5));

        assertEquals(List.of("player:Notch", "server:ALL"), wire.destinations);
    }

    // ------------------------------------------------------------ disagreement

    /**
     * The failure this whole design exists to prevent.
     *
     * <p>Registered in the other order, {@code Invite} and {@code Disband} swap ids. Without
     * the fingerprint, an invite would be decoded as a disband: sixteen bytes of UUID read
     * as a string length, and whatever came out of that put in front of an operator as a
     * reason. The receiver must refuse it and say why.
     */
    @Test
    void aRegistryInADifferentOrderIsRefusedRatherThanMisread() {
        Wire wire = new Wire("lobby");
        PacketFactory sender = parties().build(wire, new Log());

        Log log = new Log();
        PacketFactory receiver = PacketFactory.named("parties")
                .register(Disband.class, Disband::new)
                .register(Invite.class, Invite::new)
                .build(new Wire("survival"), log);

        assertNotEquals(sender.fingerprint(), receiver.fingerprint());

        List<String> delivered = new ArrayList<>();
        receiver.on(Disband.class, (from, p) -> delivered.add("disband"));
        receiver.on(Invite.class, (from, p) -> delivered.add("invite"));

        sender.send("survival", new Invite(UUID.randomUUID(), "Notch", 30));
        receiver.receive(wire.last());

        assertTrue(delivered.isEmpty(), "a packet from a disagreeing registry was delivered anyway");
        assertEquals(1, log.warnings.size());
        assertTrue(log.warnings.get(0).contains("different order")
                        || log.warnings.get(0).contains("different packets"),
                "the warning has to explain the cause: " + log.warnings.get(0));
        assertTrue(log.warnings.get(0).contains("Invite"),
                "and name what this side has registered: " + log.warnings.get(0));
    }

    /** A mismatch is a standing condition, not news. Reporting it per packet would bury the log. */
    @Test
    void aMismatchIsReportedOnceRatherThanPerPacket() {
        Wire wire = new Wire("lobby");
        PacketFactory sender = parties().build(wire, new Log());
        Log log = new Log();
        PacketFactory receiver = PacketFactory.named("parties")
                .register(Disband.class, Disband::new)
                .build(new Wire("survival"), log);

        for (int i = 0; i < 20; i++) {
            sender.send("survival", new Invite(UUID.randomUUID(), "Notch", i));
            receiver.receive(wire.last());
        }

        assertEquals(1, log.warnings.size(), "twenty packets produced " + log.warnings.size()
                + " warnings; a disagreeing pair of servers would flood the log");
    }

    /** Two plugins on one server share a channel and must not read each other's mail. */
    @Test
    void factoriesWithDifferentNamesDoNotCollide() {
        PacketFactory parties = parties().build(new Wire("lobby"), new Log());
        PacketFactory shops = PacketFactory.named("shops")
                .register(Invite.class, Invite::new)
                .register(Disband.class, Disband::new)
                .build(new Wire("lobby"), new Log());

        assertNotEquals(parties.subChannel(), shops.subChannel());
        assertNotEquals(parties.fingerprint(), shops.fingerprint(),
                "the same packets under two factory names must still be told apart");
    }

    // ------------------------------------------------------------ bad input

    @Test
    void aTruncatedPacketIsDroppedRatherThanThrown() {
        Wire wire = new Wire("lobby");
        PacketFactory sender = parties().build(wire, new Log());
        Log log = new Log();
        PacketFactory receiver = parties().build(new Wire("survival"), log);
        receiver.on(Invite.class, (from, p) -> {
            throw new AssertionError("a half a packet should not have been delivered");
        });

        byte[] full = sendAndTake(sender, wire, new Invite(UUID.randomUUID(), "Notch", 9));
        byte[] half = new byte[full.length / 2];
        System.arraycopy(full, 0, half, 0, half.length);

        receiver.receive(half);

        assertEquals(1, log.warnings.size());
        assertTrue(log.warnings.get(0).contains("Could not read"), log.warnings.get(0));
    }

    @Test
    void randomBytesAreDroppedRatherThanThrown() {
        Log log = new Log();
        PacketFactory receiver = parties().build(new Wire("survival"), log);

        receiver.receive(new byte[] {9, 9, 9, 9, 9, 9, 9, 9});
        receiver.receive(new byte[0]);

        assertEquals(2, log.warnings.size(), String.join(" | ", log.warnings));
    }

    /**
     * A packet whose read stops short still works today and will not tomorrow.
     *
     * <p>The bytes left over are the visible edge of a write and a read that disagree, and
     * the next field either side adds is where it starts costing data. Saying so while it
     * is still harmless is the only time anyone can act on it.
     */
    @Test
    void bytesLeftUnreadAreReported() {
        Wire wire = new Wire("lobby");
        PacketFactory sender = PacketFactory.named("odd")
                .register(Chatty.class, Chatty::new)
                .build(wire, new Log());

        Log log = new Log();
        List<String> delivered = new ArrayList<>();
        PacketFactory receiver = PacketFactory.named("odd")
                .register(Chatty.class, Chatty::new)
                .build(new Wire("survival"), log);
        receiver.on(Chatty.class, (from, p) -> delivered.add("arrived"));

        sender.send("survival", new Chatty());
        receiver.receive(wire.last());

        assertEquals(List.of("arrived"), delivered,
                "it should still be delivered; nothing is broken yet");
        assertTrue(log.warnings.stream().anyMatch(w -> w.contains("unread")),
                "nothing warned about the trailing bytes: " + log.warnings);
    }

    @Test
    void aHandlerThatThrowsDoesNotTakeTheChannelDown() {
        Wire wire = new Wire("lobby");
        PacketFactory sender = parties().build(wire, new Log());
        Log log = new Log();
        PacketFactory receiver = parties().build(new Wire("survival"), log);

        List<String> after = new ArrayList<>();
        receiver.on(Invite.class, (from, p) -> {
            throw new IllegalStateException("the plugin's own bug");
        });
        receiver.on(Disband.class, (from, p) -> after.add("still working"));

        receiver.receive(sendAndTake(sender, wire, new Invite(UUID.randomUUID(), "Notch", 1)));
        Disband disband = new Disband();
        disband.reason = "done";
        receiver.receive(sendAndTake(sender, wire, disband));

        assertEquals(List.of("still working"), after);
        assertEquals(1, log.warnings.size());
        assertTrue(log.warnings.get(0).contains("threw"), log.warnings.get(0));
    }

    @Test
    void aPacketWithNoHandlerIsRoutineRatherThanAProblem() {
        Wire wire = new Wire("lobby");
        PacketFactory sender = parties().build(wire, new Log());
        Log log = new Log();
        PacketFactory receiver = parties().build(new Wire("survival"), log);

        receiver.receive(sendAndTake(sender, wire, new Invite(UUID.randomUUID(), "Notch", 1)));

        assertTrue(log.warnings.isEmpty(), "one side not caring about a packet is normal");
        assertEquals(1, log.details.size());
    }

    // ------------------------------------------------------------ empty servers

    /**
     * A packet too big to forward is refused where it was written.
     *
     * <p>The length travels as an unsigned short, so an oversized one would not bounce --
     * it would be read with a wrapped length and take the rest of the message stream with
     * it. Refusing locally puts the complaint next to the packet's name, on the server
     * whose code needs changing.
     */
    @Test
    void aPacketTooBigToForwardIsRefusedWhereItWasWritten() {
        Wire wire = new Wire("lobby");
        Log log = new Log();
        PacketFactory factory = PacketFactory.named("bulk")
                .register(Huge.class, Huge::new)
                .build(wire, log);

        assertFalse(factory.send("survival", new Huge()));
        assertTrue(wire.payloads.isEmpty());
        assertEquals(1, log.warnings.size());
        assertTrue(log.warnings.get(0).contains("Huge"), log.warnings.get(0));
    }

    static final class Huge implements RelayPacket {
        @Override
        public void write(PacketBuffer out) {
            out.writeBytes(new byte[PacketFactory.MAX_BYTES + 1]);
        }

        @Override
        public void read(PacketBuffer in) {
            in.readBytes(PacketFactory.MAX_BYTES + 1);
        }
    }

    @Test
    void sendingWithNobodyToCarryItSaysSoRatherThanPretending() {
        Wire wire = new Wire("lobby");
        wire.anyoneOnline = false;
        PacketFactory factory = parties().build(wire, new Log());

        assertFalse(factory.send("survival", new Invite(UUID.randomUUID(), "Notch", 1)),
                "a plugin message needs a player connection; saying it went is worse than false");
    }

    /**
     * A packet sent before the proxy has said who we are is held, not lost.
     *
     * <p>Both facts arrive together in practice: this server learns its name by asking the
     * proxy through a player, which is the same player any packet would have travelled on.
     * So "we do not know our name yet" almost always means "nobody has joined yet", and a
     * plugin sending on startup is early rather than wrong.
     */
    @Test
    void packetsSentBeforeTheServerKnowsItsNameAreHeldAndThenGo() {
        Wire wire = new Wire(null);
        PacketFactory factory = parties().build(wire, new Log());

        assertTrue(factory.send("survival", new Invite(UUID.randomUUID(), "Early", 1)));
        assertTrue(wire.payloads.isEmpty(), "it should not have gone before we knew who we were");

        wire.name = "lobby";
        factory.flushHeld();

        assertEquals(1, wire.payloads.size());
        PacketFactory receiver = parties().build(new Wire("survival"), new Log());
        List<String> from = new ArrayList<>();
        receiver.on(Invite.class, (sender, p) -> from.add(sender));
        receiver.receive(wire.last());
        assertEquals(List.of("lobby"), from);
    }

    @Test
    void theHeldQueueIsBoundedAndSaysWhenItOverflows() {
        Wire wire = new Wire(null);
        Log log = new Log();
        PacketFactory factory = parties().build(wire, log);

        for (int i = 0; i < PacketFactory.MAX_HELD; i++) {
            assertTrue(factory.send("survival", new Invite(UUID.randomUUID(), "P" + i, i)));
        }
        assertFalse(factory.send("survival", new Invite(UUID.randomUUID(), "TooLate", 0)));
        assertEquals(1, log.warnings.size());

        wire.name = "lobby";
        factory.flushHeld();
        assertEquals(PacketFactory.MAX_HELD, wire.payloads.size());
    }

    /** Draining before resending: a packet that still cannot go must not re-queue forever. */
    @Test
    void flushingWithTheNameStillUnknownDoesNotLoop() {
        Wire wire = new Wire(null);
        PacketFactory factory = parties().build(wire, new Log());
        factory.send("survival", new Invite(UUID.randomUUID(), "Early", 1));

        factory.flushHeld();
        factory.flushHeld();

        wire.name = "lobby";
        factory.flushHeld();
        assertEquals(1, wire.payloads.size(), "the held packet was duplicated or lost");
    }

    private static byte[] sendAndTake(PacketFactory factory, Wire wire, RelayPacket packet) {
        factory.send("survival", packet);
        return wire.last();
    }
}
