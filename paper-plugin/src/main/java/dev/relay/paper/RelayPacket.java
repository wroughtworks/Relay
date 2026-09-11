package dev.relay.paper;

/**
 * One message a backend sends to another backend through the proxy.
 *
 * <p>Implement this, register it with a {@link PacketFactory} on both servers, and send
 * it by name:
 *
 * <pre>{@code
 * public final class PartyInvite implements RelayPacket {
 *     public UUID from;
 *     public String to;
 *
 *     public void write(PacketBuffer out) {
 *         out.writeUuid(from);
 *         out.writeString(to);
 *     }
 *
 *     public void read(PacketBuffer in) {
 *         from = in.readUuid();
 *         to = in.readString();
 *     }
 * }
 * }</pre>
 *
 * <p>A packet needs a no-argument constructor, because the receiving side builds a blank
 * one and reads into it. That is why {@link #read} fills {@code this} rather than
 * returning a new instance: the factory has already decided which class to make from the
 * id on the wire, and giving the packet the job of constructing itself would mean every
 * implementation repeating the same two lines.
 *
 * <p><b>Write and read must agree, in order.</b> Nothing checks it for you, and a
 * mismatch does not fail where the mistake is &mdash; it fails at whatever field happens
 * to be next, on the other server, some time later.
 */
public interface RelayPacket {

    /** Writes this packet's fields, in an order {@link #read} repeats exactly. */
    void write(PacketBuffer out);

    /** Reads this packet's fields back, in the order {@link #write} wrote them. */
    void read(PacketBuffer in);
}
