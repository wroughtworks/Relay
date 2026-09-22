package dev.relay.paper;

/**
 * A round trip to another backend and back, to prove the packet path works.
 *
 * <p>Sibling to {@link BackendApiProbe}, and for the same reason. Cross-server messaging
 * has a lot of places to fail quietly: the channel may not be registered, the proxy may
 * have nobody to deliver through, the two servers' registries may disagree. Each of those
 * looks identical from the sending side &mdash; nothing happens. This turns that into
 * either a measured round trip or a specific complaint in a log.
 *
 * <p>It is also the worked example. Everything a plugin needs to do to use
 * {@link PacketFactory} is in this file, and it is about twenty lines.
 */
final class PacketProbe {

    /** Sent to the far side. */
    static final class Ping implements RelayPacket {
        String note = "";
        long sentAt;

        @Override
        public void write(PacketBuffer out) {
            out.writeString(note);
            out.writeLong(sentAt);
        }

        @Override
        public void read(PacketBuffer in) {
            note = in.readString();
            sentAt = in.readLong();
        }
    }

    /** Sent straight back, carrying the original timestamp so the sender can subtract. */
    static final class Pong implements RelayPacket {
        String note = "";
        long sentAt;

        @Override
        public void write(PacketBuffer out) {
            out.writeString(note);
            out.writeLong(sentAt);
        }

        @Override
        public void read(PacketBuffer in) {
            note = in.readString();
            sentAt = in.readLong();
        }
    }

    private final RelayPlugin plugin;
    private final PacketFactory packets;

    PacketProbe(RelayPlugin plugin) {
        this.plugin = plugin;
        this.packets = PacketFactory.named("relay-probe")
                .register(Ping.class, Ping::new)
                .register(Pong.class, Pong::new)
                .build(plugin);

        packets.on(Ping.class, (from, ping) -> {
            plugin.report("Packet probe: ping from " + from
                    + (ping.note.isEmpty() ? "" : " (" + ping.note + ")") + "; replying");
            Pong pong = new Pong();
            pong.note = ping.note;
            pong.sentAt = ping.sentAt;
            if (!packets.send(from, pong)) {
                plugin.report("Packet probe: could not reply to " + from
                        + "; nobody is on it to carry the answer");
            }
        });

        packets.on(Pong.class, (from, pong) -> plugin.report("Packet probe: "
                + from + " answered in " + (System.currentTimeMillis() - pong.sentAt) + "ms"
                + (pong.note.isEmpty() ? "" : " (" + pong.note + ")")));
    }

    /** Sends a ping to a backend or group, and says plainly if it could not. */
    void ping(String target, String note) {
        Ping ping = new Ping();
        ping.note = note;
        ping.sentAt = System.currentTimeMillis();
        plugin.report("Packet probe: pinging '" + target + "' (registry "
                + packets.fingerprint() + ", sub-channel " + packets.subChannel() + ")");
        if (!packets.send(target, ping)) {
            plugin.report("Packet probe: nothing carried it. Either nobody is on '" + target
                    + "', or nobody is on this server to send through.");
        }
    }
}
