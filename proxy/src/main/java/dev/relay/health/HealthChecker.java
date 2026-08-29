package dev.relay.health;

import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Watches every backend, so Relay knows a server is down before a player does.
 *
 * <p>Without this, a dead backend is discovered by sending someone to it. They wait out
 * the connect timeout, get an error, and Relay tries the next candidate &mdash; and the
 * next player repeats the whole thing, because nothing was remembered. A server that has
 * been off for an hour is still first in the try order.
 *
 * <p>Checking turns that around: {@link BackendHealth.State#acceptsNewPlayers()} takes
 * failing backends out of routing (spec &sect;6.4) before anyone is sent to one.
 *
 * <h2>Hysteresis</h2>
 * A backend is only declared down after several consecutive failures. One dropped packet,
 * one GC pause long enough to miss a timeout, one moment of packet loss &mdash; any of
 * these will fail a single check on a perfectly healthy server, and reacting to one would
 * empty it. Recovery is immediate in the other direction: a server that answers is
 * available again at once, because the cost of being slow to notice recovery is players
 * piling onto fewer servers than exist.
 *
 * <h2>Threads</h2>
 * Its own small scheduler, never a Netty loop. Checks block on a socket read by design,
 * and a backend that accepts a connection then says nothing would otherwise stall traffic
 * for players who are connected and perfectly fine.
 */
public final class HealthChecker {

    private static final Logger LOG = LoggerFactory.getLogger(HealthChecker.class);

    /** Slower than this and the backend is answering, but not well. */
    private static final long DEGRADED_LATENCY_MILLIS = 1000;

    private final RelayProxy proxy;
    private final int intervalMillis;
    private final int timeoutMillis;
    private final int failuresBeforeDown;

    private ScheduledExecutorService scheduler;

    public HealthChecker(RelayProxy proxy) {
        this.proxy = proxy;
        this.intervalMillis = proxy.config().healthIntervalMillis();
        this.timeoutMillis = proxy.config().healthTimeoutMillis();
        this.failuresBeforeDown = proxy.config().healthFailuresBeforeDown();
    }

    public void start() {
        List<RegisteredServer> servers = List.copyOf(proxy.servers());
        if (servers.isEmpty()) {
            return;
        }
        // One thread per backend, capped. Checks are almost entirely spent waiting on a
        // socket, so this is about not letting one unreachable backend delay the checks
        // for every other -- which is exactly when the others matter most.
        int threads = Math.min(servers.size(), 8);
        scheduler = Executors.newScheduledThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "relay-health");
            thread.setDaemon(true);
            return thread;
        });

        for (RegisteredServer server : servers) {
            scheduler.scheduleWithFixedDelay(() -> check(server), 0, intervalMillis, TimeUnit.MILLISECONDS);
        }
        LOG.info("Health     every {}ms, {}ms timeout, down after {} consecutive failures",
                intervalMillis, timeoutMillis, failuresBeforeDown);
    }

    /**
     * Runs one check and records the result.
     *
     * <p>{@code scheduleWithFixedDelay} silently stops repeating a task that throws, so a
     * single unexpected exception here would end health checking for that backend for the
     * lifetime of the process &mdash; while everything kept reporting whatever state it
     * held at the time. Nothing may escape.
     */
    private void check(RegisteredServer server) {
        try {
            BackendHealth previous = server.health();
            StatusProbe.Result result = StatusProbe.ping(server.address(), timeoutMillis);
            BackendHealth updated = interpret(previous, result);
            server.setHealth(updated);

            if (previous.state() != updated.state()) {
                if (updated.acceptsNewPlayers()) {
                    LOG.info("Backend {} is {} again ({}ms)", server.name(), updated.state(),
                            updated.latencyMillis());
                } else {
                    LOG.warn("Backend {} is {}: {}. New players will be routed elsewhere.",
                            server.name(), updated.state(), updated.detail());
                }
                proxy.events().serverHealthChanged(server, previous, updated);
            }
        } catch (RuntimeException e) {
            LOG.error("Health check for {} threw; the checker continues", server.name(), e);
        }
    }

    private BackendHealth interpret(BackendHealth previous, StatusProbe.Result result) {
        long now = System.currentTimeMillis();

        // An operator's decision outranks anything measured. A draining backend is
        // usually perfectly healthy -- that is the point -- so a check must not
        // quietly put it back into rotation.
        if (previous.state() == BackendHealth.State.DRAINING && result.reachable()) {
            return new BackendHealth(BackendHealth.State.DRAINING, result.latencyMillis(),
                    result.players(), result.max(), result.version(), now, 0, "draining");
        }

        if (result.reachable()) {
            BackendHealth.State state = result.latencyMillis() >= DEGRADED_LATENCY_MILLIS
                    ? BackendHealth.State.DEGRADED
                    : BackendHealth.State.HEALTHY;
            return new BackendHealth(state, result.latencyMillis(), result.players(), result.max(),
                    result.version(), now, 0,
                    state == BackendHealth.State.DEGRADED
                            ? "answering in " + result.latencyMillis() + "ms" : null);
        }

        int failures = previous.failures() + 1;
        BackendHealth.State state = failures >= failuresBeforeDown
                ? BackendHealth.State.OFFLINE
                // Still in the grace period. Reported honestly rather than as healthy,
                // but not yet acted on.
                : previous.acceptsNewPlayers() ? previous.state() : BackendHealth.State.OFFLINE;
        return new BackendHealth(state, -1, previous.reportedPlayers(), previous.reportedMax(),
                previous.version(), now, failures,
                result.detail() + " (" + failures + " in a row)");
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
