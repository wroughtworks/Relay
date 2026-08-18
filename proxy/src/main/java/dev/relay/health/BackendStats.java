package dev.relay.health;

/**
 * What a backend says about its own load.
 *
 * <p>Everything here is known only to the server itself. A status ping proves a backend is
 * answering and how fast it answers a ping; it says nothing about whether the game inside
 * is running at twenty ticks a second or four. Spec &sect;9.1 wants TPS, MSPT, CPU and
 * memory on the Servers view, and &sect;6.2's resource-aware balancing needs the same
 * numbers, so a backend has to volunteer them.
 *
 * <h2>Why these can be stale</h2>
 * They arrive over a plugin message, and a plugin message needs a player connection to
 * travel on &mdash; that is how BungeeCord's channel has always worked. A backend with
 * nobody on it therefore cannot report, and its last figures simply stop being replaced.
 * {@link #reportedAt} exists so a reader can tell a quiet server from a frozen one:
 * without it, a backend that locked up two hours ago still shows the healthy TPS it had
 * at the time.
 *
 * @param tps1m      ticks per second over the last minute, or -1 if unknown
 * @param tps5m      over five minutes, or -1
 * @param tps15m     over fifteen minutes, or -1
 * @param msptMean   mean milliseconds per tick, or -1. The honest companion to TPS, which
 *                   saturates at 20 and hides a server doing 19.9 at 45ms a tick
 * @param usedMemory heap bytes in use, or -1
 * @param maxMemory  heap bytes available, or -1
 * @param cpuLoad    process CPU as a fraction of one core-equivalent, or -1
 * @param players    the backend's own count of who is on it
 * @param uptimeSeconds how long the backend process has been up, or -1
 * @param version    the server's version string, or null
 * @param reportedAt epoch millis when this arrived
 */
public record BackendStats(double tps1m, double tps5m, double tps15m, double msptMean,
                           long usedMemory, long maxMemory, double cpuLoad, int players,
                           long uptimeSeconds, String version, long reportedAt) {

    /**
     * How long a report stays worth showing.
     *
     * <p>Generous against the plugin's default reporting interval, so an ordinary hiccup
     * does not blank the display, but short enough that numbers from a backend which has
     * stopped talking are recognised as history rather than read as fact.
     */
    public static final long FRESH_FOR_MILLIS = 30_000;

    public boolean isFresh() {
        return System.currentTimeMillis() - reportedAt < FRESH_FOR_MILLIS;
    }

    public long ageSeconds() {
        return (System.currentTimeMillis() - reportedAt) / 1000;
    }
}
