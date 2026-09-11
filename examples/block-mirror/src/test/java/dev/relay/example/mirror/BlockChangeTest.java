package dev.relay.example.mirror;

import dev.relay.paper.PacketBuffer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The packet the mirror is made of.
 *
 * <p>Nothing here needs a server: what can go wrong in a packet is that {@code write} and
 * {@code read} disagree, and that is visible from a byte array. The failure it protects
 * against is not an exception either &mdash; a field written in one order and read in
 * another produces a valid-looking block at a plausible-looking coordinate, on the other
 * server, several seconds later.
 */
class BlockChangeTest {

    /**
     * Round-trips through the real buffer.
     *
     * <p>{@code PacketBuffer}'s factory methods are package-private to {@code dev.relay.paper}
     * -- they are the factory's business, not a plugin's -- so this reaches them reflectively
     * rather than widening them. A test wanting at something is not a reason to make it
     * public to everybody.
     */
    private static BlockChange roundTrip(BlockChange sent) throws Exception {
        Method writing = PacketBuffer.class.getDeclaredMethod("writing");
        Method written = PacketBuffer.class.getDeclaredMethod("written");
        Method reading = PacketBuffer.class.getDeclaredMethod(
                "reading", byte[].class, int.class, int.class);
        writing.setAccessible(true);
        written.setAccessible(true);
        reading.setAccessible(true);

        PacketBuffer out = (PacketBuffer) writing.invoke(null);
        sent.write(out);
        byte[] bytes = (byte[]) written.invoke(out);

        BlockChange back = new BlockChange();
        back.read((PacketBuffer) reading.invoke(null, bytes, 0, bytes.length));
        return back;
    }

    @Test
    void aPlacedBlockSurvivesTheTrip() throws Exception {
        BlockChange sent = new BlockChange("world", 128, 64, -4096,
                "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
                "Notch");

        BlockChange got = roundTrip(sent);

        assertEquals("world", got.world);
        assertEquals(128, got.x);
        assertEquals(64, got.y);
        assertEquals(-4096, got.z);
        assertEquals(sent.block, got.block, "block data is the whole payload; it has to be exact");
        assertEquals("Notch", got.who);
    }

    /** A break is a place of air, which is why one packet covers both directions. */
    @Test
    void aBreakIsAPlaceOfAir() throws Exception {
        BlockChange got = roundTrip(new BlockChange("nether", -1, 0, 1,
                BlockMirrorPlugin.AIR, ""));

        assertEquals(BlockMirrorPlugin.AIR, got.block);
        assertEquals("", got.who, "an empty name must stay empty rather than becoming null");
    }

    /**
     * Y below zero and coordinates past a short.
     *
     * <p>1.18 moved the world floor to -64, and a build at the far end of a world is
     * millions of blocks out. Both are ordinary, and both would be silently wrong if any
     * of these were written as a short or an unsigned value.
     */
    @Test
    void coordinatesOutsideTheEasyRangeAreExact() throws Exception {
        BlockChange got = roundTrip(new BlockChange("world", -29_999_984, -64, 29_999_984,
                "minecraft:stone", "Tester"));

        assertEquals(-29_999_984, got.x);
        assertEquals(-64, got.y);
        assertEquals(29_999_984, got.z);
    }

    /** World names come from whoever made the world; the wire format must not care. */
    @Test
    void awkwardNamesTravelIntact() throws Exception {
        BlockChange got = roundTrip(new BlockChange("world — the “good” one", 0, 0, 0,
                "minecraft:player_head[rotation=9]", "Ünicode"));

        assertEquals("world — the “good” one", got.world);
        assertEquals("Ünicode", got.who);
    }
}
