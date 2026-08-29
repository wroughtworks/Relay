package dev.relay.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;

/**
 * Tells the proxy how this server is actually doing.
 *
 * <p>The proxy can ping a backend and learn that it answers, and how quickly it answers a
 * ping. It cannot learn whether the game inside is running at twenty ticks a second or
 * four &mdash; a server deep in a GC death spiral answers status pings perfectly well.
 * TPS, MSPT, heap and CPU are known only here, so this volunteers them.
 *
 * <h2>Push, not poll</h2>
 * The proxy never asks. A plugin message needs a player connection to travel on, so a
 * request would only arrive when a player is already here, and a reply could only leave
 * under the same condition &mdash; a poll that works exactly when it was unnecessary.
 * Reporting on a timer costs one small message per interval and stops on its own when
 * the server empties.
 *
 * <p>That is also the limitation worth stating plainly: <b>an empty backend cannot
 * report</b>. Its last figures stop being replaced rather than going to zero, which is
 * why every report is stamped with the time it was sent.
 */
final class StatsReporter {

    /** Ticks per second, when the server is keeping up. */
    private static final double TARGET_TPS = 20.0;

    private final RelayPlugin plugin;
    private final RelayApi api;
    private final long intervalTicks;

    private BukkitTask task;

    StatsReporter(RelayPlugin plugin, RelayApi api, int intervalSeconds) {
        this.plugin = plugin;
        this.api = api;
        this.intervalTicks = Math.max(1, intervalSeconds) * 20L;
    }

    void start() {
        // On the main thread on purpose. Bukkit.getTPS and getAverageTickTime read the
        // scheduler's own state, and sendPluginMessage touches a player's connection;
        // neither is safe from an async task, and the work is a handful of field reads.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::report, intervalTicks, intervalTicks);
    }

    void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void report() {
        Player carrier = carrier();
        if (carrier == null) {
            // Nobody to carry it. Not worth logging: an empty server would say this
            // every interval, forever.
            return;
        }

        double[] tps = tps();
        Runtime runtime = Runtime.getRuntime();
        api.reportStats(carrier,
                tps[0], tps[1], tps[2],
                averageTickMillis(),
                runtime.totalMemory() - runtime.freeMemory(),
                runtime.maxMemory(),
                cpuLoad(),
                Bukkit.getOnlinePlayers().size(),
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000,
                Bukkit.getVersion());
    }

    /**
     * Any player will do &mdash; the message is addressed to the proxy, not to them.
     *
     * <p>The first in iteration order rather than a remembered one, because a remembered
     * player leaves and takes the reporting with them until the next restart.
     */
    private Player carrier() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            return player;
        }
        return null;
    }

    /**
     * One, five and fifteen minute averages, clamped to the target.
     *
     * <p>Spigot reports slightly over 20 when the server is idle and catching up, which
     * is true of the measurement and confusing on a dashboard. Anything at or above the
     * target means the same thing: keeping up.
     */
    private double[] tps() {
        try {
            double[] raw = Bukkit.getTPS();
            double[] clamped = new double[3];
            for (int i = 0; i < 3; i++) {
                clamped[i] = i < raw.length ? Math.min(raw[i], TARGET_TPS) : -1;
            }
            return clamped;
        } catch (Throwable unsupported) {
            // A server that is not Spigot-derived. Everything else here still works.
            return new double[] {-1, -1, -1};
        }
    }

    /**
     * Mean milliseconds per tick.
     *
     * <p>The honest companion to TPS, which saturates at 20 and so cannot distinguish a
     * server with room to spare from one finishing every tick with a millisecond left.
     * MSPT keeps climbing until the moment TPS finally drops.
     */
    private double averageTickMillis() {
        try {
            return Bukkit.getAverageTickTime();
        } catch (Throwable unsupported) {
            return -1;
        }
    }

    /**
     * Process CPU, as a fraction of one core.
     *
     * <p>Cast to {@code com.sun.management.OperatingSystemMXBean} rather than reflected
     * at. The first version looked the method up on the object's own class, which is
     * {@code com.sun.management.internal.OperatingSystemImpl} -- not exported by its
     * module, so the lookup succeeds and the invoke throws. It failed silently and
     * reported -1 on every JVM, which is indistinguishable from "this JVM cannot tell
     * you", so nothing looked wrong until the number was read on a dashboard.
     *
     * <p>The interface is part of the JDK's {@code jdk.management} module and present on
     * every mainstream JVM. The {@code instanceof} keeps a JVM without it costing one
     * number rather than the whole report.
     */
    private double cpuLoad() {
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
            double load = extended.getProcessCpuLoad();
            // Negative means "not available yet": the first sample has no interval to
            // measure against, so an idle server reports -1 once and a figure after that.
            return load < 0 ? -1 : load;
        }
        return -1;
    }
}
