package dev.relay.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProtocolUtilsTest {

    private final ByteBuf buf = Unpooled.buffer();

    @AfterEach
    void release() {
        buf.release();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 127, 128, 255, 16383, 16384, 2097151, 2097152,
            268435455, 268435456, Integer.MAX_VALUE, -1, Integer.MIN_VALUE})
    void varIntRoundTrips(int value) {
        ProtocolUtils.writeVarInt(buf, value);
        assertEquals(value, ProtocolUtils.readVarInt(buf));
        assertFalse(buf.isReadable(), "the whole VarInt should have been consumed");
    }

    /**
     * The frame encoder sizes its buffer from {@code varIntBytes} before writing the
     * prefix, so a disagreement between the two would silently under-allocate.
     */
    @ParameterizedTest
    @ValueSource(ints = {0, 127, 128, 16383, 16384, 2097151, 2097152, 268435455, 268435456, -1})
    void varIntBytesMatchesEncodedWidth(int value) {
        ProtocolUtils.writeVarInt(buf, value);
        assertEquals(buf.readableBytes(), ProtocolUtils.varIntBytes(value));
    }

    @Test
    void varIntRejectsOverWideEncoding() {
        // Six continuation bytes: a padded encoding an attacker could use to desync a
        // naive reader from the real frame boundary.
        for (int i = 0; i < 6; i++) {
            buf.writeByte(0xFF);
        }
        assertThrows(ProtocolException.class, () -> ProtocolUtils.readVarInt(buf));
    }

    @Test
    void varIntRejectsTruncatedInput() {
        buf.writeByte(0x80);
        assertThrows(ProtocolException.class, () -> ProtocolUtils.readVarInt(buf));
    }

    @Test
    void varLongRoundTrips() {
        for (long value : new long[]{0, 1, Long.MAX_VALUE, -1, Long.MIN_VALUE}) {
            ByteBuf local = Unpooled.buffer();
            try {
                ProtocolUtils.writeVarLong(local, value);
                assertEquals(value, ProtocolUtils.readVarLong(local));
            } finally {
                local.release();
            }
        }
    }

    @Test
    void stringRoundTripsIncludingMultiByteCharacters() {
        String value = "Relay — プロキシ ✅";
        ProtocolUtils.writeString(buf, value);
        assertEquals(value, ProtocolUtils.readString(buf));
        assertFalse(buf.isReadable());
    }

    @Test
    void emptyStringRoundTrips() {
        ProtocolUtils.writeString(buf, "");
        assertEquals("", ProtocolUtils.readString(buf));
    }

    /** The length prefix widens past one byte at 128 UTF-8 bytes. */
    @Test
    void longStringRoundTrips() {
        String value = "a".repeat(4000);
        ProtocolUtils.writeString(buf, value);
        assertEquals(value, ProtocolUtils.readString(buf));
    }

    @Test
    void stringRejectsOverlongDeclaredLength() {
        ProtocolUtils.writeVarInt(buf, 1_000_000);
        assertThrows(ProtocolException.class, () -> ProtocolUtils.readString(buf, 16));
    }

    @Test
    void stringRejectsLengthOverBudgetEvenWhenBytesArePresent() {
        // 20 ASCII characters read with a 16-character budget: the byte count passes the
        // cheap check, so this can only be caught after decoding.
        ProtocolUtils.writeString(buf, "a".repeat(20));
        assertThrows(ProtocolException.class, () -> ProtocolUtils.readString(buf, 16));
    }

    @Test
    void stringRejectsTruncatedPayload() {
        ProtocolUtils.writeVarInt(buf, 32);
        buf.writeBytes(new byte[8]);
        assertThrows(ProtocolException.class, () -> ProtocolUtils.readString(buf));
    }

    @Test
    void uuidRoundTrips() {
        UUID value = UUID.randomUUID();
        ProtocolUtils.writeUuid(buf, value);
        assertEquals(value, ProtocolUtils.readUuid(buf));
    }

    @Test
    void uuidRejectsTruncatedInput() {
        buf.writeBytes(new byte[15]);
        assertThrows(ProtocolException.class, () -> ProtocolUtils.readUuid(buf));
    }

    @Test
    void byteArrayRoundTrips() {
        byte[] value = {1, 2, 3, -128, 127, 0};
        ProtocolUtils.writeByteArray(buf, value);
        assertArrayEquals(value, ProtocolUtils.readByteArray(buf));
    }

    @Test
    void byteArrayRejectsDeclaredLengthOverMaximum() {
        ProtocolUtils.writeVarInt(buf, 5000);
        assertThrows(ProtocolException.class, () -> ProtocolUtils.readByteArray(buf, 256));
    }

    @Test
    void propertiesRoundTripWithAndWithoutSignatures() {
        var signed = new ProtocolUtils.GameProfileProperty("textures", "base64data", "sig");
        var unsigned = new ProtocolUtils.GameProfileProperty("relay", "value", null);

        ProtocolUtils.writeProperties(buf, java.util.List.of(signed, unsigned));
        var read = ProtocolUtils.readProperties(buf);

        assertEquals(2, read.size());
        assertEquals(signed, read.get(0));
        assertEquals(unsigned, read.get(1));
        assertFalse(buf.isReadable());
    }
}
