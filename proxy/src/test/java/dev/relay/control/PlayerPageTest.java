package dev.relay.control;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.relay.auth.GameProfile;
import dev.relay.config.ConfigLoader;
import dev.relay.net.MinecraftConnection;
import dev.relay.net.ProxyProtocolHandler;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolVersion;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The player list as a page, which is what makes it answerable on a real network.
 *
 * <p>It used to be the whole list, every row carrying a freshly walked route, rebuilt on
 * the proxy's own event loops every few seconds for every open dashboard tab. That is fine
 * for the forty fake players this project tests with and is not survivable at the scale
 * Relay is aimed at: a hundred thousand players is hundreds of megabytes of objects and
 * JSON built and thrown away, to render a table nobody can read.
 *
 * <p>So the tests here are about two things. That paging is <em>correct</em> &mdash; no row
 * lost between pages, no row shown twice, counts that describe what is not being shown.
 * And that it is actually <em>cheaper</em>, because a page that still builds every view
 * behind the scenes would have moved the problem rather than solved it.
 */
class PlayerPageTest {

    private RelayProxy proxy;
    private ControlState state;

    @AfterEach
    void stop() {
        if (proxy != null) {
            proxy.shutdown();
        }
    }

    @Test
    void pagesWithoutLosingOrRepeatingAnyone(@TempDir Path dir) throws Exception {
        start(dir);
        for (int i = 0; i < 200; i++) {
            join("Player%03d".formatted(i), "lobby");
        }

        Set<String> seen = new LinkedHashSet<>();
        List<String> order = new ArrayList<>();
        for (int offset = 0; offset < 200; offset += 50) {
            ControlState.PlayerPage page = state.players(null, null, offset, 50);
            assertEquals(200, page.total());
            assertEquals(200, page.matched(), "every player matches an empty filter");
            assertEquals(50, page.players().size());
            assertEquals(offset, page.offset());
            for (ControlState.PlayerView view : page.players()) {
                assertTrue(seen.add(view.username()),
                        view.username() + " appeared on two pages; the paging order is not stable");
                order.add(view.username());
            }
        }
        assertEquals(200, seen.size(), "paging through the list did not reach everyone");

        List<String> sorted = new ArrayList<>(order);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        assertEquals(sorted, order, "pages must come back in the order the offsets assume");
    }

    /**
     * The counts are the part a page is useless without.
     *
     * <p>Fifty rows is not an answer to "how many people are online". {@code total} and
     * {@code matched} are what let a client say what the fifty are a slice of, and they
     * have to describe everyone rather than the slice.
     */
    @Test
    void saysHowManyItIsNotShowing(@TempDir Path dir) throws Exception {
        start(dir);
        for (int i = 0; i < 120; i++) {
            join("Player%03d".formatted(i), i % 2 == 0 ? "survival-01" : "survival-02");
        }

        ControlState.PlayerPage page = state.players(null, null, 0, 10);
        assertEquals(10, page.players().size());
        assertEquals(120, page.total());
        assertEquals(120, page.matched());

        ControlState.PlayerPage narrowed = state.players(null, "survival-01", 0, 10);
        assertEquals(10, narrowed.players().size());
        assertEquals(120, narrowed.total(), "total is everyone, not everyone matching");
        assertEquals(60, narrowed.matched());
    }

    @Test
    void anOffsetPastTheEndIsAnEmptyPageRatherThanAnError(@TempDir Path dir) throws Exception {
        start(dir);
        join("Solo", "lobby");

        ControlState.PlayerPage page = state.players(null, null, 500, 50);
        assertTrue(page.players().isEmpty());
        assertEquals(1, page.total());
        assertEquals(1, page.matched());
        assertEquals(500, page.offset());
    }

    /** A group narrows to its members, because "who is on survival" is the real question. */
    @Test
    void aGroupNameMeansItsMembers(@TempDir Path dir) throws Exception {
        start(dir);
        join("OnLobby", "lobby");
        join("OnOne", "survival-01");
        join("OnTwo", "survival-02");

        ControlState.PlayerPage pool = state.players(null, "survival", 0, 50);
        assertEquals(2, pool.matched());
        assertEquals(List.of("OnOne", "OnTwo"),
                pool.players().stream().map(ControlState.PlayerView::username).toList());

        ControlState.PlayerPage member = state.players(null, "survival-02", 0, 50);
        assertEquals(List.of("OnTwo"),
                member.players().stream().map(ControlState.PlayerView::username).toList());
    }

    /**
     * A backend nobody has matches nobody, which is not the same as matching everybody.
     *
     * <p>The failure worth guarding against is a filter that silently does nothing: a
     * dashboard asking for one backend and being handed the whole network would look
     * like it worked right up until someone believed it.
     */
    @Test
    void anUnknownServerMatchesNobody(@TempDir Path dir) throws Exception {
        start(dir);
        join("Someone", "lobby");

        ControlState.PlayerPage page = state.players(null, "not-a-server", 0, 50);
        assertTrue(page.players().isEmpty());
        assertEquals(0, page.matched());
        assertEquals(1, page.total(), "the filter narrows the page, not the truth");
    }

    /** The same fields the browser used to search, matched the same way. */
    @Test
    void searchesNameBackendAndRouteIdCaseInsensitively(@TempDir Path dir) throws Exception {
        start(dir);
        ConnectedPlayer notch = join("Notch", "lobby");
        join("Herobrine", "survival-01");

        assertEquals(1, state.players("notch", null, 0, 50).matched());
        assertEquals(1, state.players("NOTCH", null, 0, 50).matched());
        assertEquals(1, state.players("otc", null, 0, 50).matched(), "a substring, not a prefix");
        assertEquals(1, state.players("survival-01", null, 0, 50).matched(),
                "the backend name is searchable, as it was in the browser");
        assertEquals(2, state.players("  ", null, 0, 50).matched(), "blank is not a filter");
        assertEquals(0, state.players("nobody", null, 0, 50).matched());

        ControlState.PlayerPage byRoute = state.players(
                notch.routeId().toUpperCase(Locale.ROOT), null, 0, 50);
        assertEquals(1, byRoute.matched(), "a route id is the one token that greps a session");
        assertEquals("Notch", byRoute.players().get(0).username());
    }

    /**
     * Upstream totals cover everyone, not the page.
     *
     * <p>The topology map used to count edges by scanning the full player list in the
     * browser, which worked only because the browser had everyone. It cannot once the list
     * is fifty rows, so the proxy totals them on its way past &mdash; and the number has to
     * be the network's, or the map would quietly shrink as the table did.
     */
    @Test
    void upstreamTotalsCountEveryoneNotJustThePage(@TempDir Path dir) throws Exception {
        start(dir);
        for (int i = 0; i < 6; i++) {
            proxy.players().add(viaEdge("Edged%d".formatted(i), new InetSocketAddress("10.0.0.7", 40000 + i)));
        }
        join("Direct", "lobby");

        ControlState.PlayerPage page = state.players(null, null, 0, 2);
        assertEquals(2, page.players().size(), "the page is still a page");

        assertEquals(1, page.edges().size(), "one upstream, however many arrived through it");
        assertEquals("10.0.0.7", page.edges().get(0).name());
        assertEquals(6, page.edges().get(0).players(),
                "the edge total followed the page instead of covering the network");
    }

    @Test
    void noUpstreamMeansNoEdgesRatherThanANullOne(@TempDir Path dir) throws Exception {
        start(dir);
        join("Direct", "lobby");

        assertNotNull(state.players(null, null, 0, 50).edges());
        assertTrue(state.players(null, null, 0, 50).edges().isEmpty());
    }

    /** Asking for more than the cap gets the cap, not an exception and not the network. */
    @Test
    void clampsAnUnreasonableLimit(@TempDir Path dir) throws Exception {
        start(dir);
        for (int i = 0; i < 600; i++) {
            join("Player%03d".formatted(i), "lobby");
        }

        ControlState.PlayerPage page = state.players(null, null, 0, 10_000);
        assertEquals(ControlState.MAX_LIMIT, page.limit());
        assertEquals(ControlState.MAX_LIMIT, page.players().size());
        assertEquals(600, page.total());
    }

    /**
     * A page has to cost a page, or none of this was worth doing.
     *
     * <p>Measured through Gson, because that is where the cost actually is. Building the
     * views is the smaller half: what the proxy then does is serialise them into one JSON
     * line and push it down a socket, and it is that line -- tens of megabytes of it on a
     * large network -- that the old shape made unavoidable. Timing the construction alone
     * would flatter the change by measuring the part that was never the problem.
     *
     * <p>The floor is the scan: counting how many players match means looking at all of
     * them however few are returned. So this asserts a large constant factor rather than a
     * proportional one, and the honest reading is that the saving grows with the network
     * while the scan stays linear.
     */
    @Test
    void aPageCostsAPageRatherThanTheWholeNetwork(@TempDir Path dir) throws Exception {
        start(dir);
        List<ConnectedPlayer> everyone = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            everyone.add(join("Player%05d".formatted(i), i % 2 == 0 ? "survival-01" : "survival-02"));
        }
        Gson gson = new GsonBuilder().serializeNulls().create();

        long whole = time(20, () -> {
            List<ControlState.PlayerView> views = new ArrayList<>(everyone.size());
            for (ConnectedPlayer player : everyone) {
                views.add(state.view(player));
            }
            assertFalse(gson.toJson(views).isEmpty());
        });
        long page = time(20, () -> assertFalse(
                gson.toJson(state.players(null, null, 0, 50)).isEmpty()));

        assertTrue(page * 8 < whole,
                "a fifty-row page took " + page + "ms against " + whole + "ms to answer with all "
                        + everyone.size() + "; the paging is not saving the work it exists to save");
    }

    /**
     * And the answer has to be a page's worth of bytes.
     *
     * <p>The blunt version of the test above, and the one that cannot be talked out of by
     * a fast machine: whatever it cost to build, what leaves the process is one line on a
     * socket, and that line used to grow with the network.
     */
    @Test
    void theAnswerIsAPageOfBytesNotANetworkOfThem(@TempDir Path dir) throws Exception {
        start(dir);
        List<ControlState.PlayerView> everyone = new ArrayList<>();
        for (int i = 0; i < 5_000; i++) {
            everyone.add(state.view(join("Player%05d".formatted(i), "survival-01")));
        }
        Gson gson = new GsonBuilder().serializeNulls().create();

        int asAList = gson.toJson(everyone).length();
        int asAPage = gson.toJson(state.players(null, null, 0, 50)).length();

        assertTrue(asAPage * 50L < asAList,
                "one page is " + asAPage + " characters against " + asAList
                        + " for the whole list; that is not a page");
    }

    // ------------------------------------------------------------ helpers

    private static long time(int rounds, Runnable work) {
        for (int i = 0; i < 3; i++) {
            work.run();
        }
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            work.run();
        }
        return Math.max(1, (System.nanoTime() - start) / 1_000_000);
    }

    private void start(Path dir) throws Exception {
        Files.writeString(dir.resolve("relay.toml"), """
                bind = "127.0.0.1:%d"
                node-name = "test-node"
                forwarding-mode = "none"
                health.enabled = false
                control.enabled = false

                [servers]
                lobby = "127.0.0.1:25566"
                survival-01 = "127.0.0.1:25567"
                survival-02 = "127.0.0.1:25568"
                """.formatted(freePort()));
        proxy = new RelayProxy(ConfigLoader.load(dir.resolve("relay.toml")));
        proxy.start();
        state = new ControlState(proxy);
    }

    private ConnectedPlayer join(String name, String server) {
        ConnectedPlayer player = player(name, new EmbeddedChannel());
        RegisteredServer target = proxy.server(server).orElseThrow();
        player.setConnectedServer(new ServerConnection(target, player));
        proxy.players().add(player);
        return player;
    }

    /**
     * A player who arrived through something that announced itself with a PROXY header.
     *
     * <p>Driven through the real handler rather than reaching for the field it sets: that
     * field is package-private, and the alternative -- widening it for a test -- would make
     * the production type more mutable to prove something about a dashboard.
     */
    private ConnectedPlayer viaEdge(String name, InetSocketAddress edge) {
        EmbeddedChannel channel = new EmbeddedChannel() {
            @Override
            protected SocketAddress remoteAddress0() {
                return edge;
            }
        };
        ConnectedPlayer player = player(name, channel);
        channel.pipeline().addLast(new ProxyProtocolHandler(player.connection()));
        channel.writeInbound(new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY,
                HAProxyProxiedProtocol.TCP4, "203.0.113.9", "198.51.100.1", 51000, 25565));
        assertNotNull(player.proxiedFrom(), "the PROXY header did not take");
        return player;
    }

    private static ConnectedPlayer player(String name, EmbeddedChannel channel) {
        MinecraftConnection connection =
                new MinecraftConnection(channel, PacketDirection.SERVERBOUND, null, null);
        return new ConnectedPlayer(connection,
                new GameProfile(UUID.randomUUID(), name, List.of()),
                ProtocolVersion.MINECRAFT_1_20_2, null);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }
}
