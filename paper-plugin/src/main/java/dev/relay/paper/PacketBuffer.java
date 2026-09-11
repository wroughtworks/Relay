package dev.relay.paper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The bytes of one packet, being written or being read.
 *
 * <p>One type for both directions, the way Minecraft's own buffers work: a
 * {@link RelayPacket} only ever uses the half that matches what it was handed, and
 * reaching for the other one is a programming error that shows up on the first call
 * rather than as confusing bytes on another server.
 *
 * <p>Nothing here throws a checked exception. The underlying streams are backed by byte
 * arrays and cannot fail for the reasons {@code IOException} exists to describe, so
 * making every {@code write} implementation declare it would be ceremony protecting
 * against nothing. Running off the end of a buffer is real, though, and arrives as an
 * unchecked {@link PacketException} the factory catches and reports against the packet
 * that caused it.
 */
public final class PacketBuffer {

    /** A packet that could not be written or read as its class says it should be. */
    public static final class PacketException extends RuntimeException {
        PacketException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final DataOutputStream out;
    private final ByteArrayOutputStream sink;
    private final DataInputStream in;

    private PacketBuffer(DataOutputStream out, ByteArrayOutputStream sink, DataInputStream in) {
        this.out = out;
        this.sink = sink;
        this.in = in;
    }

    static PacketBuffer writing() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream(64);
        return new PacketBuffer(new DataOutputStream(sink), sink, null);
    }

    static PacketBuffer reading(byte[] bytes, int offset, int length) {
        return new PacketBuffer(null, null,
                new DataInputStream(new ByteArrayInputStream(bytes, offset, length)));
    }

    byte[] written() {
        return sink.toByteArray();
    }

    /** How much is left unread, which is how the factory notices a short read. */
    int remaining() {
        try {
            return in.available();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
    }

    // ------------------------------------------------------------------ writing

    public PacketBuffer writeBoolean(boolean value) {
        return write(() -> out.writeBoolean(value));
    }

    public PacketBuffer writeByte(int value) {
        return write(() -> out.writeByte(value));
    }

    public PacketBuffer writeShort(int value) {
        return write(() -> out.writeShort(value));
    }

    public PacketBuffer writeInt(int value) {
        return write(() -> out.writeInt(value));
    }

    public PacketBuffer writeLong(long value) {
        return write(() -> out.writeLong(value));
    }

    public PacketBuffer writeFloat(float value) {
        return write(() -> out.writeFloat(value));
    }

    public PacketBuffer writeDouble(double value) {
        return write(() -> out.writeDouble(value));
    }

    /**
     * A length-prefixed UTF-8 string, with no 65535-byte ceiling.
     *
     * <p>Not {@code DataOutputStream.writeUTF}, which has one and throws when a string
     * exceeds it. Chat, item names and serialised data all get there eventually, and a
     * message format that works until someone writes a long enough sign is worse than one
     * that never did.
     */
    public PacketBuffer writeString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        return writeBytes(bytes);
    }

    /** A variable-length int: one byte for values under 128, which most ids and sizes are. */
    public PacketBuffer writeVarInt(int value) {
        int remaining = value;
        do {
            int part = remaining & 0x7F;
            remaining >>>= 7;
            writeByte(remaining == 0 ? part : part | 0x80);
        } while (remaining != 0);
        return this;
    }

    public PacketBuffer writeUuid(UUID value) {
        writeLong(value.getMostSignificantBits());
        return writeLong(value.getLeastSignificantBits());
    }

    /** The constant name, not the ordinal: reordering an enum must not reroute data. */
    public PacketBuffer writeEnum(Enum<?> value) {
        return writeString(value.name());
    }

    public PacketBuffer writeBytes(byte[] value) {
        return write(() -> out.write(value));
    }

    /** Length-prefixed, so the reader does not need to be told how many to expect. */
    public PacketBuffer writeStrings(List<String> values) {
        writeVarInt(values.size());
        for (String value : values) {
            writeString(value);
        }
        return this;
    }

    // ------------------------------------------------------------------ reading

    public boolean readBoolean() {
        return read(in::readBoolean);
    }

    public byte readByte() {
        return read(in::readByte);
    }

    public short readShort() {
        return read(in::readShort);
    }

    public int readInt() {
        return read(in::readInt);
    }

    public long readLong() {
        return read(in::readLong);
    }

    public float readFloat() {
        return read(in::readFloat);
    }

    public double readDouble() {
        return read(in::readDouble);
    }

    public String readString() {
        return new String(readBytes(readVarInt()), StandardCharsets.UTF_8);
    }

    public int readVarInt() {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int part = readByte() & 0xFF;
            value |= (part & 0x7F) << shift;
            if ((part & 0x80) == 0) {
                return value;
            }
        }
        throw new PacketException("a varint ran past five bytes; the buffer is not what it claims", null);
    }

    public UUID readUuid() {
        return new UUID(readLong(), readLong());
    }

    public <E extends Enum<E>> E readEnum(Class<E> type) {
        String name = readString();
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknown) {
            throw new PacketException(
                    type.getSimpleName() + " has no constant '" + name
                            + "'; the two servers are running different versions of it", unknown);
        }
    }

    public byte[] readBytes(int length) {
        byte[] bytes = new byte[length];
        read(() -> {
            in.readFully(bytes);
            return null;
        });
        return bytes;
    }

    public List<String> readStrings() {
        int size = readVarInt();
        List<String> values = new ArrayList<>(Math.min(size, 64));
        for (int i = 0; i < size; i++) {
            values.add(readString());
        }
        return values;
    }

    // ------------------------------------------------------------------ plumbing

    private interface Write {
        void run() throws IOException;
    }

    private interface Read<T> {
        T run() throws IOException;
    }

    private PacketBuffer write(Write action) {
        if (out == null) {
            throw new IllegalStateException("this buffer is being read from, not written to");
        }
        try {
            action.run();
        } catch (IOException impossible) {
            throw new PacketException("could not write a packet", impossible);
        }
        return this;
    }

    private <T> T read(Read<T> action) {
        if (in == null) {
            throw new IllegalStateException("this buffer is being written to, not read from");
        }
        try {
            return action.run();
        } catch (IOException ranOut) {
            throw new PacketException(
                    "the packet ended before it had been fully read; write and read disagree",
                    ranOut);
        }
    }
}
