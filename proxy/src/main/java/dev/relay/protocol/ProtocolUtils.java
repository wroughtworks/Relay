package dev.relay.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Wire-format primitives shared by every packet.
 *
 * <p>Every read here is bounds-checked. This is the layer spec &sect;8 is talking about
 * when it says validate before relaying: a hostile client should fail here, on the
 * proxy, and never reach a backend.
 */
public final class ProtocolUtils {

    /** Minecraft strings are counted in UTF-16 code units, and each may encode to 3 bytes. */
    private static final int MAX_STRING_BYTES_PER_CHAR = 3;

    public static final int DEFAULT_MAX_STRING_LENGTH = 32767;

    private ProtocolUtils() {
    }

    // ---------------------------------------------------------------- var ints

    public static int readVarInt(ByteBuf buf) {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (!buf.isReadable()) {
                throw new ProtocolException("VarInt truncated");
            }
            byte b = buf.readByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new ProtocolException("VarInt too wide (>5 bytes)");
    }

    public static void writeVarInt(ByteBuf buf, int value) {
        while (true) {
            if ((value & ~0x7F) == 0) {
                buf.writeByte(value);
                return;
            }
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    /** Encoded width of {@code value} in bytes, 1..5. */
    public static int varIntBytes(int value) {
        return switch (Integer.numberOfLeadingZeros(value)) {
            case 0, 1, 2, 3 -> 5;
            case 4, 5, 6, 7, 8, 9, 10 -> 4;
            case 11, 12, 13, 14, 15, 16, 17 -> 3;
            case 18, 19, 20, 21, 22, 23, 24 -> 2;
            default -> 1;
        };
    }

    public static long readVarLong(ByteBuf buf) {
        long result = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            if (!buf.isReadable()) {
                throw new ProtocolException("VarLong truncated");
            }
            byte b = buf.readByte();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
        }
        throw new ProtocolException("VarLong too wide (>10 bytes)");
    }

    public static void writeVarLong(ByteBuf buf, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                buf.writeByte((int) value);
                return;
            }
            buf.writeByte((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    // ---------------------------------------------------------------- strings

    public static String readString(ByteBuf buf) {
        return readString(buf, DEFAULT_MAX_STRING_LENGTH);
    }

    public static String readString(ByteBuf buf, int maxLength) {
        int length = readVarInt(buf);
        if (length < 0) {
            throw new ProtocolException("String has negative length " + length);
        }
        if (length > maxLength * MAX_STRING_BYTES_PER_CHAR) {
            throw new ProtocolException(
                    "String byte length " + length + " exceeds maximum " + (maxLength * MAX_STRING_BYTES_PER_CHAR));
        }
        if (!buf.isReadable(length)) {
            throw new ProtocolException("String truncated: wanted " + length + " bytes, have " + buf.readableBytes());
        }
        String value = buf.toString(buf.readerIndex(), length, StandardCharsets.UTF_8);
        buf.skipBytes(length);
        if (value.length() > maxLength) {
            throw new ProtocolException("String length " + value.length() + " exceeds maximum " + maxLength);
        }
        return value;
    }

    public static void writeString(ByteBuf buf, CharSequence value) {
        int byteLength = ByteBufUtil.utf8Bytes(value);
        writeVarInt(buf, byteLength);
        buf.writeCharSequence(value, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- byte arrays

    public static byte[] readByteArray(ByteBuf buf) {
        return readByteArray(buf, Short.MAX_VALUE);
    }

    public static byte[] readByteArray(ByteBuf buf, int maxLength) {
        int length = readVarInt(buf);
        if (length < 0) {
            throw new ProtocolException("Byte array has negative length " + length);
        }
        if (length > maxLength) {
            throw new ProtocolException("Byte array length " + length + " exceeds maximum " + maxLength);
        }
        if (!buf.isReadable(length)) {
            throw new ProtocolException(
                    "Byte array truncated: wanted " + length + " bytes, have " + buf.readableBytes());
        }
        byte[] value = new byte[length];
        buf.readBytes(value);
        return value;
    }

    public static void writeByteArray(ByteBuf buf, byte[] value) {
        writeVarInt(buf, value.length);
        buf.writeBytes(value);
    }

    /** Reads whatever is left, without a length prefix. Used for plugin-message payloads. */
    public static byte[] readRemaining(ByteBuf buf) {
        byte[] value = new byte[buf.readableBytes()];
        buf.readBytes(value);
        return value;
    }

    // ---------------------------------------------------------------- uuids

    public static UUID readUuid(ByteBuf buf) {
        if (!buf.isReadable(16)) {
            throw new ProtocolException("UUID truncated");
        }
        return new UUID(buf.readLong(), buf.readLong());
    }

    public static void writeUuid(ByteBuf buf, UUID value) {
        buf.writeLong(value.getMostSignificantBits());
        buf.writeLong(value.getLeastSignificantBits());
    }

    // ---------------------------------------------------------------- misc

    public static <T extends Enum<T>> T readEnum(ByteBuf buf, T[] values) {
        int ordinal = readVarInt(buf);
        if (ordinal < 0 || ordinal >= values.length) {
            throw new ProtocolException("Enum ordinal " + ordinal + " out of range [0," + values.length + ")");
        }
        return values[ordinal];
    }

    public static List<GameProfileProperty> readProperties(ByteBuf buf) {
        int count = readVarInt(buf);
        if (count < 0 || count > 32) {
            throw new ProtocolException("Property count " + count + " out of range");
        }
        List<GameProfileProperty> properties = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = readString(buf, 64);
            String value = readString(buf, 32767);
            String signature = buf.readBoolean() ? readString(buf, 32767) : null;
            properties.add(new GameProfileProperty(name, value, signature));
        }
        return properties;
    }

    public static void writeProperties(ByteBuf buf, List<GameProfileProperty> properties) {
        writeVarInt(buf, properties.size());
        for (GameProfileProperty property : properties) {
            writeString(buf, property.name());
            writeString(buf, property.value());
            boolean signed = property.signature() != null;
            buf.writeBoolean(signed);
            if (signed) {
                writeString(buf, property.signature());
            }
        }
    }

    /** A single texture/skin property attached to a game profile. */
    public record GameProfileProperty(String name, String value, String signature) {
    }
}
