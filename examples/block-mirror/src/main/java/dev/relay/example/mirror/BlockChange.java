package dev.relay.example.mirror;

import dev.relay.paper.PacketBuffer;
import dev.relay.paper.RelayPacket;

/**
 * One block, changed somewhere else.
 *
 * <p>Both directions of the mirror are this packet. A break is a place of {@code air},
 * because at the point the far side applies it there is no difference worth two classes:
 * the same world, the same coordinate, a block data string to set. Two packets would mean
 * two handlers doing the same three lines and two chances to fix a bug in only one.
 *
 * <h2>Why the block is a string</h2>
 * {@code minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]} is
 * Bukkit's own round-trippable form, and the only one that survives being sent to a server
 * that may be a different build. Block <em>ids</em> are internal, change between versions,
 * and would silently mirror a staircase as something else the first time the two servers
 * were not identical.
 */
public final class BlockChange implements RelayPacket {

    public String world;
    public int x;
    public int y;
    public int z;
    /** Bukkit's {@code BlockData.getAsString()}, or {@code minecraft:air} for a break. */
    public String block;
    /** Who did it, for the log on the far side. Not trusted for anything. */
    public String who = "";

    public BlockChange() {
    }

    public BlockChange(String world, int x, int y, int z, String block, String who) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.block = block;
        this.who = who;
    }

    @Override
    public void write(PacketBuffer out) {
        out.writeString(world);
        out.writeInt(x);
        // Y is a short's worth of range in every version that exists, but writing it as
        // one would break the day somebody builds above 32767, and an int here costs two
        // bytes on a packet that is already mostly a block-data string.
        out.writeInt(y);
        out.writeInt(z);
        out.writeString(block);
        out.writeString(who);
    }

    @Override
    public void read(PacketBuffer in) {
        world = in.readString();
        x = in.readInt();
        y = in.readInt();
        z = in.readInt();
        block = in.readString();
        who = in.readString();
    }

    @Override
    public String toString() {
        return block + " at " + world + " " + x + "," + y + "," + z;
    }
}
