package dev.relay.health;

import dev.relay.config.BalanceStrategy;
import dev.relay.config.ConfigLoader;
import dev.relay.config.RelayConfig.ServerEntry;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerGroup;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Knowing a backend is down before a player finds out.
 *
 * <p>Without checking, a dead backend is discovered by sending someone to it: they wait
 * out the connect timeout, get an error, and the next player repeats the whole thing
 * because nothing was remembered. These cover the two halves that fix it — noticing, and
 * then actually routing around what was noticed.
 */
class HealthCheckerTest {

    private RelayProxy proxy;
    private final List<ServerSocket> sockets = new ArrayList<>();

    @AfterEach
    void stop() throws IOException {
        if (proxy != null) {
            proxy.shutdown();
        }
        for (ServerSocket socket : sockets) {
            socket.close();
        }
    }

    @Test
    void readsPlayerCountsAndLatencyFromAStatusResponse(@TempDir Path dir) throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.serve("{\"players\":{\"online\":7,\"max\":50},\"version\":{\"name\":\"Paper 1.20.2\"}}");

        proxy = start(dir, backend.port(), 1000);
        RegisteredServer server = proxy.server("lobby").orElseThrow();
        BackendHealth health = await(server, BackendHealth.State.HEALTHY);

        assertEquals(7, health.reportedPlayers(),
                "the backend's own count is worth keeping: above Relay's, it means players "
                        + "are reaching it without passing through this proxy");
        assertEquals(50, health.reportedMax());
        assertEquals("Paper 1.20.2", health.version());
        assertTrue(health.latencyMillis() >= 0, "a successful ping should be timed");
        assertTrue(server.acceptsNewPlayers());
    }

    /**
     * One failure is not an outage.
     *
     * <p>A dropped packet, a GC pause long enough to miss a timeout, a moment of loss —
     * any of these fails a single check on a perfectly healthy server. Reacting to one
     * would empty it, so the state only moves after several in a row.
     */
    @Test
    void oneMissedCheckDoesNotTakeABackendOutOfRotation(@TempDir Path dir) throws Exception {
        int deadPort = freePort();          // nothing is listening here, ever
        proxy = start(dir, deadPort, 1000);
        RegisteredServer server = proxy.server("lobby").orElseThrow();

        BackendHealth afterOne = awaitFailures(server, 1);
        assertTrue(afterOne.acceptsNewPlayers(),
                "a single failure must not take a backend out of routing, got " + afterOne.state());
        assertNotEquals(BackendHealth.State.OFFLINE, afterOne.state());

        BackendHealth eventually = await(server, BackendHealth.State.OFFLINE);
        assertFalse(eventually.acceptsNewPlayers());
        assertTrue(eventually.failures() >= 3);
        assertTrue(eventually.detail().contains("in a row"),
                "the reason should say it was repeated, got: " + eventually.detail());
    }

    /**
     * A failing member is demoted within its group, not deleted from it.
     *
     * <p>Spec 6.4 wants unhealthy backends out of routing. Demotion achieves that while
     * leaving them as a last resort, so a network whose health checking is itself broken
     * degrades to trying anyway rather than refusing every join.
     */
    @Test
    void anUnhealthyMemberSinksToTheBottomOfItsGroup() {
        RegisteredServer first = server("survival-01");
        RegisteredServer second = server("survival-02");
        ServerGroup group = new ServerGroup("survival", List.of(first, second));

        assertEquals(List.of(first, second), group.ordered(BalanceStrategy.FIRST_AVAILABLE));

        first.setHealth(BackendHealth.unknown().withState(BackendHealth.State.OFFLINE));
        assertEquals(List.of(second, first), group.ordered(BalanceStrategy.FIRST_AVAILABLE),
                "the healthy member should lead, and the failing one should still be offered last");
    }

    /**
     * Health orders within the strategy rather than over it.
     *
     * <p>The health sort runs last and is stable, so it partitions what the strategy
     * chose. Applied the other way round it would have been silently useless — the
     * strategy sort would have sorted straight over it — and nothing would have looked
     * wrong until an emptier server sat behind a busier one.
     */
    @Test
    void healthPartitionsTheStrategyRatherThanReplacingIt() {
        RegisteredServer busy = server("survival-01");
        RegisteredServer quiet = server("survival-02");
        RegisteredServer quietest = server("survival-03");
        ServerGroup group = new ServerGroup("survival", List.of(busy, quiet, quietest));

        quietest.setHealth(BackendHealth.unknown().withState(BackendHealth.State.OFFLINE));

        // Among the healthy two, least-players still decides; the offline one is last
        // despite being the emptiest of all.
        List<RegisteredServer> ordered = group.ordered(BalanceStrategy.LEAST_PLAYERS);
        assertEquals(quietest, ordered.get(2), "an offline server should be last however empty it is");
        assertTrue(ordered.indexOf(busy) < 2 && ordered.indexOf(quiet) < 2);
    }

    /** Draining is an operator's decision, and a passing check must not undo it. */
    @Test
    void aDrainingBackendStaysDrainingWhileItAnswers(@TempDir Path dir) throws Exception {
        FakeBackend backend = new FakeBackend();
        backend.serve("{\"players\":{\"online\":1,\"max\":20}}");

        proxy = start(dir, backend.port(), 1000);
        RegisteredServer server = proxy.server("lobby").orElseThrow();
        await(server, BackendHealth.State.HEALTHY);

        server.setHealth(server.health().withState(BackendHealth.State.DRAINING));
        BackendHealth after = awaitChecksAfter(server, System.currentTimeMillis());

        assertEquals(BackendHealth.State.DRAINING, after.state(),
                "a health check must not put a deliberately drained backend back into rotation");
        assertFalse(server.acceptsNewPlayers());
    }

    // ------------------------------------------------------------ helpers

    private RelayProxy start(Path dir, int backendPort, int interval) throws Exception {
        Files.writeString(dir.resolve("relay.toml"), """
                bind = "127.0.0.1:%d"
                forwarding-mode = "none"

                [health]
                enabled = true
                interval = %d
                timeout = 1000
                failures-before-down = 3

                [servers]
                lobby = "127.0.0.1:%d"
                """.formatted(freePort(), interval, backendPort));
        RelayProxy started = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        started.start();
        return started;
    }

    private static BackendHealth await(RegisteredServer server, BackendHealth.State state) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (server.health().state() == state) {
                return server.health();
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError(server.name() + " never reached " + state
                + "; it is " + server.health().state() + " (" + server.health().detail() + ")");
    }

    private static BackendHealth awaitFailures(RegisteredServer server, int failures) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (server.health().failures() >= failures) {
                return server.health();
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError(server.name() + " never recorded " + failures + " failure(s)");
    }

    private static BackendHealth awaitChecksAfter(RegisteredServer server, long since) throws Exception {
        for (int i = 0; i < 200; i++) {
            if (server.health().checkedAt() > since) {
                return server.health();
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError(server.name() + " was not checked again");
    }

    private static RegisteredServer server(String name) {
        return new RegisteredServer(new ServerEntry(name, new InetSocketAddress("127.0.0.1", 25565)));
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    /** A backend that answers status pings, and only status pings, for as long as asked. */
    private final class FakeBackend {

        private final ServerSocket socket;
        private final AtomicInteger pings = new AtomicInteger();

        FakeBackend() throws IOException {
            this.socket = new ServerSocket();
            this.socket.bind(new InetSocketAddress("127.0.0.1", 0));
            sockets.add(this.socket);
        }

        int port() {
            return socket.getLocalPort();
        }

        void serve(String json) {
            Thread thread = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (Socket connection = socket.accept()) {
                        connection.setSoTimeout(5000);
                        InputStream in = connection.getInputStream();
                        readFrame(in).release();                    // handshake
                        readFrame(in).release();                    // status request

                        ByteBuf response = Unpooled.buffer();
                        response.writeByte(0x00);
                        ProtocolUtils.writeString(response, json);
                        writeFrame(connection.getOutputStream(), response);
                        connection.getOutputStream().flush();
                        pings.incrementAndGet();
                    } catch (IOException expected) {
                        // The listener was closed, or a probe hung up. Either way, done.
                        return;
                    }
                }
            }, "fake-status-backend");
            thread.setDaemon(true);
            thread.start();
        }
    }

    private static void writeFrame(OutputStream out, ByteBuf payload) throws IOException {
        try {
            ByteBuf framed = Unpooled.buffer();
            try {
                ProtocolUtils.writeVarInt(framed, payload.readableBytes());
                framed.writeBytes(payload);
                byte[] bytes = new byte[framed.readableBytes()];
                framed.readBytes(bytes);
                out.write(bytes);
            } finally {
                framed.release();
            }
        } finally {
            payload.release();
        }
    }

    private static ByteBuf readFrame(InputStream in) throws IOException {
        DataInputStream data = new DataInputStream(in);
        int length = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte b = data.readByte();
            length |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
        }
        byte[] payload = new byte[length];
        data.readFully(payload);
        return Unpooled.wrappedBuffer(payload);
    }
}
