package dev.relay.protocol.nbt;

import com.google.gson.JsonParser;
import dev.relay.protocol.ComponentCodec;
import dev.relay.protocol.ProtocolVersion;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NbtWriterTest {

    private final ByteBuf buf = Unpooled.buffer();

    @AfterEach
    void release() {
        buf.release();
    }

    /**
     * Checked against a hand-assembled expectation rather than a round trip, since Relay
     * has no NBT reader to round trip against — a bug here would produce bytes a client
     * rejects, with nothing on the proxy side to notice.
     */
    @Test
    void writesASimpleCompoundExactly() {
        NbtWriter.writeRoot(buf, JsonParser.parseString("{\"text\":\"hi\"}"));

        assertEquals(
                "0a"          // root: TAG_Compound, no name
                        + "08"        // entry type: TAG_String
                        + "0004" + "74657874"   // name "text"
                        + "0002" + "6869"       // value "hi"
                        + "00",       // TAG_End
                ByteBufUtil.hexDump(buf));
    }

    @Test
    void writesBooleansAsBytesAndWholeNumbersAsInts() {
        NbtWriter.writeRoot(buf, JsonParser.parseString("{\"bold\":true,\"color\":16}"));

        assertEquals(
                "0a"
                        + "01" + "0004" + "626f6c64" + "01"     // TAG_Byte "bold" = 1
                        + "03" + "0005" + "636f6c6f72" + "00000010"  // TAG_Int "color" = 16
                        + "00",
                ByteBufUtil.hexDump(buf));
    }

    @Test
    void writesNestedListsOfCompounds() {
        NbtWriter.writeRoot(buf, JsonParser.parseString("{\"extra\":[{\"text\":\"a\"},{\"text\":\"b\"}]}"));

        String hex = ByteBufUtil.hexDump(buf);
        // TAG_List of TAG_Compound, length 2.
        assertTrue(hex.contains("09" + "0005" + "6578747261" + "0a" + "00000002"),
                "expected a 2-element compound list, got " + hex);
    }

    @Test
    void skipsNullFields() {
        NbtWriter.writeRoot(buf, JsonParser.parseString("{\"text\":\"hi\",\"color\":null}"));
        assertEquals("0a" + "08" + "0004" + "74657874" + "0002" + "6869" + "00", ByteBufUtil.hexDump(buf));
    }

    @Test
    void rejectsHeterogeneousLists() {
        assertThrows(RuntimeException.class,
                () -> NbtWriter.writeRoot(buf, JsonParser.parseString("{\"a\":[\"x\",1]}")));
    }

    /**
     * The version split is the reason this writer exists: 1.20.2 still expects a JSON
     * string where later versions expect NBT.
     */
    @Test
    void componentCodecPicksEncodingByVersion() {
        Component message = Component.text("hello", NamedTextColor.RED);

        ByteBuf json = Unpooled.buffer();
        ByteBuf nbt = Unpooled.buffer();
        try {
            ComponentCodec.write(json, message, ProtocolVersion.MINECRAFT_1_20_2);
            ComponentCodec.write(nbt, message, ProtocolVersion.MINECRAFT_1_20_3);

            // A JSON string frame starts with a VarInt length; an NBT root starts with
            // its tag type, 0x0a for a compound.
            assertEquals(0x0a, nbt.getUnsignedByte(0));
            assertNotEquals(0x0a, json.getUnsignedByte(0));
        } finally {
            json.release();
            nbt.release();
        }
    }
}
