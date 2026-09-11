package dev.relay.example.mirror;

import dev.relay.paper.PacketFactory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Place a block on one server, and it appears on another.
 *
 * <p>A worked example of {@link PacketFactory}, and the smallest one that is still a real
 * thing rather than a ping: two servers, a shared registry of one packet, and a change on
 * one becoming the same change on the other. Break a block and it breaks there too.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Both servers run this plugin, and therefore build the same factory from the same
 *       code. That is what makes the ids agree.</li>
 *   <li>A player places or breaks a block. The listener turns it into a
 *       {@link BlockChange} and sends it to whatever {@code mirror-to} names.</li>
 *   <li>The far side receives it, hops to the main thread, and sets the block.</li>
 * </ol>
 *
 * <h2>The loop that is not there</h2>
 * The obvious fear is a place that mirrors, which mirrors back, forever. It does not,
 * because {@link BlockPlaceEvent} and {@link BlockBreakEvent} are <em>player</em> events:
 * setting a block through the API fires neither. So the applied change is silent by
 * construction rather than by a flag that has to be got right. That is worth knowing before
 * anyone extends this to react to block changes from other sources, where it would no
 * longer hold.
 *
 * <h2>What this is not</h2>
 * Not a world-sync plugin. It mirrors the two events it listens to and nothing else: no
 * pistons, no water, no TNT, no chunks that were already different when you started, and no
 * reconciliation if the two worlds drift. It is a demonstration that a typed packet crosses
 * the proxy and does something visible on the other side.
 */
public final class BlockMirrorPlugin extends JavaPlugin implements Listener {

    /** Broken blocks travel as this, so one packet covers both directions of the mirror. */
    static final String AIR = "minecraft:air";

    private PacketFactory packets;
    private String mirrorTo;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mirrorTo = getConfig().getString("mirror-to", "");
        if (mirrorTo == null || mirrorTo.isBlank()) {
            getLogger().warning("mirror-to is not set in config.yml, so there is nowhere to "
                    + "mirror to. Set it to a backend or group name as the proxy knows it.");
            return;
        }

        // One packet, registered the same way on both servers. The factory stamps every
        // message with a fingerprint of this registry, so a server running an older build
        // of this plugin is refused with an explanation rather than decoding it wrong.
        packets = PacketFactory.named("block-mirror")
                .register(BlockChange.class, BlockChange::new)
                .build(this);
        packets.on(BlockChange.class, this::apply);

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("mirrortest").setExecutor(this::test);
        getLogger().info("Mirroring block changes to '" + mirrorTo + "' (registry "
                + packets.fingerprint() + "). Both servers must print the same number.");
    }

    // ------------------------------------------------------------------ sending

    /**
     * Sent at MONITOR, after everything that could cancel the event has had its say.
     *
     * <p>At any earlier priority this would mirror placements that a protection plugin then
     * refused, and the two worlds would disagree from the first build somebody was not
     * allowed to make. {@code ignoreCancelled} covers the ones already refused by the time
     * this runs.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        send(block, block.getBlockData().getAsString(), event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        send(event.getBlock(), AIR, event.getPlayer().getName());
    }

    private void send(Block block, String data, String who) {
        Location at = block.getLocation();
        BlockChange change = new BlockChange(block.getWorld().getName(),
                at.getBlockX(), at.getBlockY(), at.getBlockZ(), data, who);
        if (!packets.send(mirrorTo, change)) {
            // Almost always "nobody is on this server", which cannot happen while somebody
            // is placing blocks -- so in practice this means nobody is on the other side,
            // and the proxy dropped it there. Either way it is worth saying once per
            // change rather than leaving a silently one-sided world.
            getLogger().fine("Could not mirror " + change + " to " + mirrorTo);
        }
    }

    /**
     * {@code /mirrortest <x> <y> <z> [block] [world]} -- send one change by hand.
     *
     * <p>Proving a mirror works otherwise needs two people in two worlds at the same time.
     * This takes the same path a placed block does, from {@link #send} onwards, so it
     * exercises everything except the two event handlers: encode, forward, proxy, decode,
     * main thread, set. Runnable from the console, which is how it can be driven over RCON.
     */
    private boolean test(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("/" + label + " <x> <y> <z> [block] [world]");
            return true;
        }
        String world = args.length > 4 ? args[4] : Bukkit.getWorlds().get(0).getName();
        String block = args.length > 3 ? args[3] : "minecraft:stone";
        int x;
        int y;
        int z;
        try {
            x = Integer.parseInt(args[0]);
            y = Integer.parseInt(args[1]);
            z = Integer.parseInt(args[2]);
        } catch (NumberFormatException notANumber) {
            sender.sendMessage("Coordinates have to be whole numbers.");
            return true;
        }

        BlockChange change = new BlockChange(world, x, y, z, block, sender.getName());
        boolean sent = packets.send(mirrorTo, change);
        sender.sendMessage(sent
                ? "Sent " + change + " to " + mirrorTo
                : "Nothing carried it: nobody is online on this server to send through.");
        return true;
    }

    // ------------------------------------------------------------------ receiving

    /**
     * Applies a change from the other server.
     *
     * <p>Hopped to the main thread before touching a world. Whether a plugin message
     * arrives there is a detail of the server implementation rather than a promise, and
     * setting a block from the wrong thread is the kind of bug that works in testing and
     * corrupts a chunk on a busy server.
     */
    private void apply(String from, BlockChange change) {
        Bukkit.getScheduler().runTask(this, () -> {
            World world = Bukkit.getWorld(change.world);
            if (world == null) {
                // The two servers do not have the same worlds. Worth saying plainly: it
                // presents as "the mirror does not work" with nothing else to go on.
                getLogger().warning("Cannot mirror " + change + " from " + from
                        + ": this server has no world called '" + change.world + "'");
                return;
            }
            BlockData data;
            try {
                data = Bukkit.createBlockData(change.block);
            } catch (IllegalArgumentException unknown) {
                // A block this version does not have, or has differently. Named rather
                // than swallowed, since it means the two servers are not the same build.
                getLogger().warning("Cannot mirror " + change + " from " + from
                        + ": this server does not recognise '" + change.block + "'");
                return;
            }
            world.getBlockAt(change.x, change.y, change.z).setBlockData(data, false);
            getLogger().fine("Mirrored " + change + " from " + from
                    + (change.who.isEmpty() ? "" : " (" + change.who + ")"));
        });
    }
}
