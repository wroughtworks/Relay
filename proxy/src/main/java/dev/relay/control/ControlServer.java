package dev.relay.control;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.relay.log.LogTail;
import dev.relay.proxy.ProxyEvents;
import dev.relay.proxy.RelayProxy;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.util.function.Consumer;
import java.util.Base64;

/**
 * The private channel companions use to watch and question the proxy.
 *
 * <p>Relay is a Minecraft proxy, and everything that is not that belongs outside it. The
 * dashboard needs Jetty, Javalin and Kotlin &mdash; four and a half megabytes against
 * Relay's own two hundred kilobytes of code &mdash; and a Discord bot would drag in a
 * second HTTP and WebSocket stack that would eventually meet Netty. Neither belongs in the
 * process carrying player traffic.
 *
 * <p>So they run as separate processes, and this is how they see anything. It is
 * deliberately the smallest thing that could work: newline-delimited JSON, on Netty, which
 * is already here. No HTTP server, no serialisation framework, no dependency at all beyond
 * the Gson already present for text components. Moving the dashboard out would have bought
 * nothing if the channel back had needed its own web server.
 *
 * <h2>Access</h2>
 * Loopback only, and gated on a token generated fresh at every start and handed to
 * companions through their environment. Loopback alone is not enough on a shared machine,
 * where any local process could otherwise read the player list; the token means a
 * companion has to have been started by this proxy.
 *
 * <h2>Protocol</h2>
 * One JSON object per line, both directions.
 * <ul>
 *   <li>{@code {"type":"hello","token":"..."}} &rarr; {@code {"type":"welcome",...}}</li>
 *   <li>{@code {"type":"query","id":1,"what":"servers"}} &rarr;
 *       {@code {"type":"result","id":1,"data":[...]}}</li>
 *   <li>{@code {"type":"event",...}} pushed as they happen, unrequested</li>
 *   <li>{@code {"type":"goodbye"}} when the proxy is stopping, so a companion can exit
 *       cleanly rather than being killed</li>
 * </ul>
 */
public final class ControlServer {

    private static final Logger LOG = LoggerFactory.getLogger(ControlServer.class);

    /** Bumped when the shape changes, so a stale companion says so instead of misreading. */
    public static final int PROTOCOL_VERSION = 1;

    /** A line longer than this is not a control message; something is wrong upstream. */
    private static final int MAX_LINE = 1 << 20;

    private final RelayProxy proxy;
    private final ControlState state;
    private final Gson gson = new GsonBuilder().serializeNulls().create();
    private final String token = generateToken();

    /** Authenticated companions. Netty's group handles concurrent close for us. */
    private final ChannelGroup subscribers = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    private final ProxyEvents.Listener listener = new ProxyEvents.Listener() {
        @Override
        public void onPlayerEvent(ProxyEvents.PlayerEvent event) {
            publish(Event.player(event.kind().name(), state.view(event.player()),
                    event.from() == null ? null : event.from().name(),
                    event.to() == null ? null : event.to().name()));
        }

        @Override
        public void onServerEvent(ProxyEvents.ServerEvent event) {
            publish(Event.server(event.kind().name(), event.server().name(),
                    event.before().state().name(), event.after().state().name(),
                    event.after().detail()));
        }
    };

    /**
     * Forwards each log line as it is written.
     *
     * <p>Held as a field so it can be removed on stop: the buffer is static and outlives
     * any one proxy, so a listener left behind by a stopped proxy would keep writing to a
     * closed channel group for the life of the JVM. Tests start and stop several.
     */
    private final Consumer<LogTail.Line> logListener = line -> {
        if (!subscribers.isEmpty()) {
            write(new LogEvent("log", line.at(), line.level(), line.logger(),
                    line.thread(), line.message()));
        }
    };

    private Channel listener0;

    public ControlServer(RelayProxy proxy) {
        this.proxy = proxy;
        this.state = new ControlState(proxy);
    }

    public String token() {
        return token;
    }

    /** @return the bound port, or -1 if the server is not running */
    public int port() {
        return listener0 == null ? -1 : ((InetSocketAddress) listener0.localAddress()).getPort();
    }

    /**
     * Binds the control listener.
     *
     * @param group the proxy's own event loops. Control traffic is a few small lines a
     *              second at most, so giving it a thread pool of its own would cost more
     *              than it carries
     */
    public void start(EventLoopGroup group, InetSocketAddress bind) throws InterruptedException {
        listener0 = new ServerBootstrap()
                .group(group)
                .channel(proxy.transport().serverChannelType())
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        channel.pipeline()
                                .addLast(new LineBasedFrameDecoder(MAX_LINE))
                                .addLast(new StringDecoder(CharsetUtil.UTF_8))
                                .addLast(new StringEncoder(CharsetUtil.UTF_8))
                                .addLast(new Session());
                    }
                })
                .bind(bind.getHostString(), bind.getPort())
                .sync()
                .channel();

        proxy.events().addListener(listener);
        LogTail.addListener(logListener);
        LOG.info("Control    {}:{} (companions authenticate with a per-start token)",
                bind.getHostString(), port());
    }

    /**
     * Tells every companion the proxy is going away, and gives them a moment to act on it.
     *
     * <p>A companion that is told can close its own sockets and flush its own state. One
     * that is merely killed cannot, and on Windows there is no signal to kill it politely
     * with &mdash; which is exactly the trap {@code relay.py} already hit and had to solve
     * with RCON. Saying goodbye first turns a hard kill into a fallback rather than the
     * only option.
     */
    public void stop() {
        proxy.events().removeListener(listener);
        LogTail.removeListener(logListener);
        if (!subscribers.isEmpty()) {
            write(new Event("goodbye", null, null, null, null, null));
        }
        subscribers.close().awaitUninterruptibly(2000);
        if (listener0 != null) {
            listener0.close().syncUninterruptibly();
            listener0 = null;
        }
    }

    private void publish(Event event) {
        if (!subscribers.isEmpty()) {
            write(event);
        }
    }

    /**
     * Writes one newline-delimited JSON message to every companion.
     *
     * <p>Takes {@code Object} because there are two envelopes on this channel now and
     * they have nothing in common but being serialisable. A shared supertype would exist
     * only to satisfy this signature.
     */
    private void write(Object event) {
        String line = gson.toJson(event) + "\n";
        subscribers.writeAndFlush(line);
    }

    /**
     * A log line on its way to a companion.
     *
     * <p>Its own envelope rather than another {@code kind} on {@link Event}, because these
     * arrive orders of magnitude more often than a player joining and share none of its
     * fields. Squeezing them into one shape would mean a record that is nine-tenths null
     * on every message.
     */
    private record LogEvent(String type, long at, String level, String logger,
                            String thread, String message) {
    }

    /** One envelope for everything pushed, so a companion reads one shape. */
    private record Event(String type, String kind, ControlState.PlayerView player,
                         String from, String to, String server) {

        static Event player(String kind, ControlState.PlayerView player, String from, String to) {
            return new Event("event", kind, player, from, to, null);
        }

        static Event server(String kind, String server, String from, String to, String detail) {
            return new Event("event", kind, null, from, to + (detail == null ? "" : " (" + detail + ")"),
                    server);
        }
    }

    /** One companion's connection. Nothing is sent until it has proved who it is. */
    private final class Session extends SimpleChannelInboundHandler<String> {

        private boolean authenticated;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, String line) {
            if (line.isBlank()) {
                return;
            }
            JsonObject message;
            try {
                message = JsonParser.parseString(line).getAsJsonObject();
            } catch (RuntimeException notJson) {
                LOG.debug("Control client sent a line that is not JSON; closing");
                ctx.close();
                return;
            }

            String type = message.has("type") ? message.get("type").getAsString() : "";
            if (!authenticated) {
                authenticate(ctx, type, message);
                return;
            }
            if ("query".equals(type)) {
                answer(ctx, message);
            }
            // Anything else is ignored rather than an error: a companion built against a
            // newer proxy may send things this one has never heard of, and that should
            // cost it a feature rather than its connection.
        }

        private void authenticate(ChannelHandlerContext ctx, String type, JsonObject message) {
            String offered = message.has("token") ? message.get("token").getAsString() : "";
            if (!"hello".equals(type) || !constantTimeEquals(offered, token)) {
                LOG.warn("Rejected a control connection from {} with a bad or missing token",
                        ctx.channel().remoteAddress());
                ctx.close();
                return;
            }
            authenticated = true;
            subscribers.add(ctx.channel());
            JsonObject welcome = new JsonObject();
            welcome.addProperty("type", "welcome");
            welcome.addProperty("protocol", PROTOCOL_VERSION);
            welcome.addProperty("relay", RelayProxy.version());
            ctx.writeAndFlush(welcome + "\n");
            LOG.debug("Control client authenticated; {} watching", subscribers.size());
        }

        private void answer(ChannelHandlerContext ctx, JsonObject message) {
            String what = message.has("what") ? message.get("what").getAsString() : "";
            Object data = switch (what) {
                case "overview" -> state.overview();
                case "servers" -> state.servers();
                case "groups" -> state.groups();
                case "players" -> state.players();
                case "metrics" -> state.metrics();
                case "log" -> LogTail.recent();
                // The only query that takes arguments. Kept as optional fields on the
                // same envelope rather than a second message type, since "ask for a
                // collection" is one idea whichever way it is narrowed.
                case "history" -> state.history(
                        message.has("uuid") && !message.get("uuid").isJsonNull()
                                ? message.get("uuid").getAsString() : null,
                        message.has("limit") ? message.get("limit").getAsInt() : 50);
                default -> null;
            };

            JsonObject result = new JsonObject();
            result.addProperty("type", "result");
            if (message.has("id")) {
                result.add("id", message.get("id"));
            }
            result.addProperty("what", what);
            result.add("data", data == null ? null : gson.toJsonTree(data));
            ctx.writeAndFlush(gson.toJson(result) + "\n");
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.debug("Control connection failed", cause);
            ctx.close();
        }
    }

    /**
     * Compares without leaking length or position through timing.
     *
     * <p>The token is short-lived and loopback-only, so this is belt and braces rather
     * than the thing standing between an attacker and the player list. It costs nothing.
     */
    private static boolean constantTimeEquals(String offered, String expected) {
        byte[] a = offered.getBytes(CharsetUtil.UTF_8);
        byte[] b = expected.getBytes(CharsetUtil.UTF_8);
        int difference = a.length ^ b.length;
        for (int i = 0; i < a.length && i < b.length; i++) {
            difference |= a[i] ^ b[i];
        }
        return difference == 0;
    }

    /** Fresh every start, so a token that leaks into a log stops working at the restart. */
    private static String generateToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
