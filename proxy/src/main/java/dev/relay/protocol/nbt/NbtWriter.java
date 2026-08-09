package dev.relay.protocol.nbt;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.relay.protocol.ProtocolException;
import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Writes a JSON tree as <em>network NBT</em>.
 *
 * <p>From 1.20.3 onward text components travel the wire as NBT rather than a JSON
 * string. Relay never needs to build components by hand at the NBT level &mdash; it
 * serialises an Adventure component to JSON and converts here &mdash; so a generic
 * one-way converter is all that is required.
 *
 * <p>Network NBT differs from file NBT in one respect: the root tag is written as a
 * type byte followed immediately by its payload, with no name.
 */
public final class NbtWriter {

    private static final byte TAG_END = 0;
    private static final byte TAG_BYTE = 1;
    private static final byte TAG_INT = 3;
    private static final byte TAG_DOUBLE = 6;
    private static final byte TAG_STRING = 8;
    private static final byte TAG_LIST = 9;
    private static final byte TAG_COMPOUND = 10;

    private NbtWriter() {
    }

    /** Writes {@code element} as a nameless root tag. */
    public static void writeRoot(ByteBuf buf, JsonElement element) {
        byte type = typeOf(element);
        buf.writeByte(type);
        writePayload(buf, type, element);
    }

    private static byte typeOf(JsonElement element) {
        if (element.isJsonObject()) {
            return TAG_COMPOUND;
        }
        if (element.isJsonArray()) {
            return TAG_LIST;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isString()) {
                return TAG_STRING;
            }
            if (primitive.isBoolean()) {
                return TAG_BYTE;
            }
            // Component fields are only ever ints (colors, indexes) or floats/doubles.
            double value = primitive.getAsDouble();
            return value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) <= Integer.MAX_VALUE
                    ? TAG_INT
                    : TAG_DOUBLE;
        }
        throw new ProtocolException("Cannot represent JSON null as NBT");
    }

    private static void writePayload(ByteBuf buf, byte type, JsonElement element) {
        switch (type) {
            case TAG_BYTE -> buf.writeByte(element.getAsBoolean() ? 1 : 0);
            case TAG_INT -> buf.writeInt(element.getAsInt());
            case TAG_DOUBLE -> buf.writeDouble(element.getAsDouble());
            case TAG_STRING -> writeModifiedUtf8(buf, element.getAsString());
            case TAG_LIST -> writeList(buf, element.getAsJsonArray());
            case TAG_COMPOUND -> writeCompound(buf, element.getAsJsonObject());
            default -> throw new ProtocolException("Unhandled NBT tag type " + type);
        }
    }

    private static void writeList(ByteBuf buf, JsonArray array) {
        if (array.isEmpty()) {
            buf.writeByte(TAG_END);
            buf.writeInt(0);
            return;
        }
        // NBT lists are homogeneous; component arrays ("extra", "with") always are.
        byte elementType = typeOf(array.get(0));
        buf.writeByte(elementType);
        buf.writeInt(array.size());
        for (JsonElement element : array) {
            if (typeOf(element) != elementType) {
                throw new ProtocolException("Heterogeneous JSON array cannot be encoded as an NBT list");
            }
            writePayload(buf, elementType, element);
        }
    }

    private static void writeCompound(ByteBuf buf, JsonObject object) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonElement value = entry.getValue();
            if (value.isJsonNull()) {
                continue;
            }
            byte type = typeOf(value);
            buf.writeByte(type);
            writeModifiedUtf8(buf, entry.getKey());
            writePayload(buf, type, value);
        }
        buf.writeByte(TAG_END);
    }

    /**
     * NBT strings are length-prefixed with an unsigned short. Relay only ever writes
     * component text, which is well inside the 65535-byte ceiling, but the check stays
     * so a pathological kick message fails here rather than producing a corrupt frame.
     */
    private static void writeModifiedUtf8(ByteBuf buf, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 65535) {
            throw new ProtocolException("NBT string is " + bytes.length + " bytes, maximum is 65535");
        }
        buf.writeShort(bytes.length);
        buf.writeBytes(bytes);
    }
}
