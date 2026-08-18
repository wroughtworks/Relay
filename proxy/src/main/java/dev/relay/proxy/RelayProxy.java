package dev.relay.proxy;

import dev.relay.auth.SessionAuthenticator;
import dev.relay.command.CommandManager;
import dev.relay.config.ForwardingMode;
import dev.relay.config.RelayConfig;
import dev.relay.config.RelayConfig.ProtocolOverride;
import dev.relay.config.RelayConfig.ServerEntry;
import dev.relay.dashboard.DashboardServer;
import dev.relay.net.ConnectionInitializer;
import dev.relay.net.Encryption;
import dev.relay.net.MinecraftConnection;
import dev.relay.net.Transport;
import dev.relay.forwarding.ModernForwarding;
import dev.relay.health.HealthChecker;
import dev.relay.protocol.PacketDirection;
import dev.relay.protocol.ProtocolState;
import dev.relay.protocol.ProtocolVersion;
import dev.relay.protocol.StateRegistry;
import dev.relay.session.BackendLoginSessionHandler;
import dev.relay.session.HandshakeSessionHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** The proxy itself: listener, backend registry, player registry, shared services. */
public final class RelayProxy {

    private static final Logger LOG = LoggerFactory.getLogger(RelayProxy.class);

    private final RelayConfig config;
    private final Map<String, RegisteredServer> servers = new LinkedHashMap<>();
    private final Map<String, ServerGroup> groups = new LinkedHashMap<>();
    private final PlayerRegistry players = new PlayerRegistry();
    private final ProxyEvents events = new ProxyEvents();
    private DashboardServer dashboard;
    private HealthChecker healthChecker;
    private final SessionAuthenticator authenticator = new SessionAuthenticator();
    private final CommandManager commands;
    private final KeyPair keyPair;
    private final Transport transport;
    private final long startedAt = System.currentTimeMillis();

    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel listener;

    public RelayProxy(RelayConfig config) {
        this.config = config;
        this.transport = Transport.best();
        this.keyPair = config.onlineMode() ? Encryption.generateKeyPair() : null;
        for (ServerEntry entry : config.servers().values()) {
            servers.put(entry.name().toLowerCase(Locale.ROOT), new RegisteredServer(entry));
        }
        config.groups().forEach((name, members) -> {
            List<RegisteredServer> resolved = new ArrayList<>(members.size());
            for (String member : members) {
                resolved.add(servers.get(member.toLowerCase(Locale.ROOT)));
            }
            groups.put(name.toLowerCase(Locale.ROOT), new ServerGroup(name, resolved));
        });
        this.commands = new CommandManager(this);
        applyProtocolOverrides();
    }

    // ------------------------------------------------------------ accessors

    public RelayConfig config() {
        return config;
    }

    public PlayerRegistry players() {
        return players;
    }

    public ProxyEvents events() {
        return events;
    }

    public CommandManager commands() {
        return commands;
    }

    public SessionAuthenticator authenticator() {
        return authenticator;
    }

    public KeyPair keyPair() {
        return keyPair;
    }

    public long uptimeMillis() {
        return System.currentTimeMillis() - startedAt;
    }

    /**
     * The build's version, from the jar manifest.
     *
     * <p>Logged at startup so a report can be tied to a build. Running an older jar than
     * expected looks exactly like a fix not working.
     */
    public static String version() {
        String version = RelayProxy.class.getPackage().getImplementationVersion();
        return version == null ? "dev build (run from classes, no manifest)" : version;
    }

    public Optional<RegisteredServer> server(String name) {
        return Optional.ofNullable(servers.get(name.toLowerCase(Locale.ROOT)));
    }

    public Collection<RegisteredServer> servers() {
        return servers.values();
    }

    public Optional<ServerGroup> group(String name) {
        return Optional.ofNullable(groups.get(name.toLowerCase(Locale.ROOT)));
    }

    public Collection<ServerGroup> groups() {
        return groups.values();
    }

    /**
     * Turns a destination a player asked for into the backends to try, best first.
     *
     * <p>One name may mean one backend or several. Returning a list rather than a single
     * choice is what lets a group be balanced and failed over by the same code: the
     * preferred member leads, and anything that refuses simply falls through to the next.
     *
     * @return the candidates in the order they should be tried, or empty if the name is
     *         neither a backend nor a group
     */
    public List<RegisteredServer> resolve(String name) {
        RegisteredServer server = servers.get(name.toLowerCase(Locale.ROOT));
        if (server != null) {
            return List.of(server);
        }
        ServerGroup group = groups.get(name.toLowerCase(Locale.ROOT));
        return group == null ? List.of() : group.ordered(config.balance());
    }

    /**
     * Resolves a whole try order, flattened and de-duplicated.
     *
     * <p>De-duplication matters once groups exist: a fallback list of
     * {@code ["survival", "lobby"]} where lobby is also in the survival group would
     * otherwise attempt the same backend twice, and report its failure twice.
     */
    public List<RegisteredServer> resolveAll(List<String> names) {
        List<RegisteredServer> candidates = new ArrayList<>();
        for (String name : names) {
            List<RegisteredServer> resolved = resolve(name);
            if (resolved.isEmpty()) {
                LOG.warn("'{}' is neither a backend nor a group; skipping it", name);
                continue;
            }
            for (RegisteredServer candidate : resolved) {
                if (!candidates.contains(candidate)) {
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    /**
     * The single backend a destination names right now.
     *
     * <p>For callers that move one player and report one outcome &mdash; {@code /server},
     * {@code /send}, the plugin-message APIs. They get the balanced choice without having
     * to understand groups.
     */
    public Optional<RegisteredServer> select(String name) {
        List<RegisteredServer> resolved = resolve(name);
        return resolved.isEmpty() ? Optional.empty() : Optional.of(resolved.get(0));
    }

    // ------------------------------------------------------------ lifecycle

    public void start() throws InterruptedException {
        bossGroup = transport.createGroup("relay-boss", 1);
        workerGroup = transport.createGroup("relay-worker", 0);

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(transport.serverChannelType())
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                // Backpressure: stop reading from a player whose backend has fallen
                // behind, rather than buffering their traffic without limit.
                .childOption(ChannelOption.AUTO_READ, true)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        MinecraftConnection connection = ConnectionInitializer.initialize(
                                channel, PacketDirection.SERVERBOUND, config.readTimeoutMillis(),
                                config.proxyProtocolReceive(), false,
                                config.traceCloses() ? "player " + channel.remoteAddress() : null);
                        connection.setSessionHandler(new HandshakeSessionHandler(RelayProxy.this, connection));
                    }
                });

        InetSocketAddress bind = config.bind();
        ChannelFuture future = bootstrap.bind(bind.getHostString(), bind.getPort()).sync();
        listener = future.channel();

        // Deliberately verbose. Every backend-side problem so far has come down to which
        // build is running, which config it read, or which secret that config held --
        // none of which were visible from the logs while diagnosing them.
        LOG.info("Relay {} starting", version());
        LOG.info("Config     {}", config.sourcePath());
        LOG.info("Listening  {}:{} ({} transport)", bind.getHostString(), bind.getPort(),
                transport.name().toLowerCase(Locale.ROOT));
        LOG.info("Protocol   {} | {} mode", ProtocolVersion.supportedRange(),
                config.onlineMode() ? "online" : "offline");
        LOG.info("Forwarding {}{}", config.forwardingMode().configName(),
                config.forwardingMode() == ForwardingMode.MODERN
                        ? " (secret fingerprint " + ModernForwarding.fingerprint(config.forwardingSecret())
                                + " -- a hash, not the secret; must match the backend's)"
                        : "");
        LOG.info("PROXY      send={} receive={}", config.proxyProtocolSend(), config.proxyProtocolReceive());
        LOG.info("Backends   {}", String.join(", ", servers.keySet()));
        if (!groups.isEmpty()) {
            for (ServerGroup group : groups.values()) {
                LOG.info("Group      {}", group);
            }
            LOG.info("Balancing  {}", config.balance().configName());
        }

        if (config.healthEnabled()) {
            healthChecker = new HealthChecker(this);
            healthChecker.start();
        }

        // Last, so a dashboard failure cannot stop the listener that matters from
        // already being open.
        if (config.dashboardEnabled()) {
            dashboard = new DashboardServer(this);
            dashboard.start();
        }

        if (config.proxyProtocolSend()) {
            LOG.info("Sending PROXY protocol headers to backends. Each backend must accept them "
                    + "(proxies.proxy-protocol: true in paper-global.yml), or it will reject the connection.");
        } else {
            LOG.info("Not sending PROXY protocol headers. A backend with proxies.proxy-protocol: true will "
                    + "accept the connection and then never answer; set proxy-protocol-send = true for those.");
        }
    }

    /** Idempotent: the shutdown hook and an explicit {@code stop} both land here. */
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        LOG.info("Shutting down");
        if (dashboard != null) {
            dashboard.stop();
        }
        if (healthChecker != null) {
            healthChecker.stop();
        }
        for (ConnectedPlayer player : players.snapshot()) {
            player.disconnect(Component.text("Proxy is restarting"));
        }
        if (listener != null) {
            listener.close().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        shutdownLatch.countDown();
    }

    /**
     * Blocks until {@link #shutdown()} runs.
     *
     * <p>Every Netty thread is a daemon, so without something holding the main thread the
     * JVM would exit the moment {@code main} returned &mdash; which is exactly what
     * happens under {@code docker run -d}, where stdin is closed immediately.
     */
    public void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }

    // ------------------------------------------------------------ backend connections

    /**
     * Opens a backend connection and drives it through login on the player's behalf.
     *
     * <p>The returned future completes when the backend has either accepted the login or
     * refused it &mdash; not when the player has finished switching. Handing the player
     * over is the caller's job, because only the caller knows whether this is a first
     * join or a mid-game switch.
     */
    public CompletableFuture<ConnectionResult> connect(ConnectedPlayer player, RegisteredServer target) {
        ServerConnection attempt = new ServerConnection(target, player);

        // Claim the pending-switch slot up front. Two overlapping switches would both try
        // to walk the same client through configuration state, and the second would find
        // the client already gone from play.
        if (!player.beginConnect(attempt)) {
            return CompletableFuture.completedFuture(new ConnectionResult.Rejected(target,
                    Component.text("You are already connecting to a server")));
        }
        // Release it on any outcome that is not a completed handover. The success path
        // clears it at the moment the player actually enters play on the new backend.
        attempt.result().whenComplete((result, error) -> {
            if (error != null || result == null || !result.successful()) {
                player.endConnect(attempt);
            }
        });

        Bootstrap bootstrap = new Bootstrap()
                .group(player.connection().channel().eventLoop())
                .channel(transport.clientChannelType())
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.connectTimeoutMillis())
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        MinecraftConnection connection = ConnectionInitializer.initialize(
                                channel, PacketDirection.CLIENTBOUND, config.readTimeoutMillis(),
                                false, config.proxyProtocolSend(),
                                config.traceCloses()
                                        ? "backend " + target.name() + " for " + player.username()
                                        : null);
                        connection.setVersion(player.version());
                        attempt.setConnection(connection);
                    }
                });

        InetSocketAddress address = target.address();
        bootstrap.connect(address.getHostString(), address.getPort()).addListener((ChannelFuture future) -> {
            if (!future.isSuccess()) {
                attempt.markUnreachable(future.cause());
                return;
            }
            // Leave the connection in handshake state. BackendLoginSessionHandler moves
            // it to login itself, between writing the handshake and the login packet;
            // setting it here would make the handshake unencodable, since that packet
            // only has an id in handshake state.
            attempt.connection().setSessionHandler(new BackendLoginSessionHandler(this, attempt));
        });

        return attempt.result();
    }

    // ------------------------------------------------------------ startup wiring

    /**
     * Applies {@code [protocol.overrides]} before the listener opens.
     *
     * <p>Deliberately mutating shared static state, which is safe only because it happens
     * during construction, before any connection can observe it.
     */
    private void applyProtocolOverrides() {
        for (ProtocolOverride override : config.protocolOverrides()) {
            ProtocolVersion version = ProtocolVersion.byId(override.protocolVersion());
            if (version == null) {
                throw new IllegalArgumentException("protocol override '" + override.key()
                        + "' targets unsupported protocol version " + override.protocolVersion());
            }
            ProtocolState state = parseState(override);
            PacketDirection direction = parseDirection(override);
            StateRegistry.override(state, direction, override.packet(), version, override.packetId());
            LOG.warn("Protocol override in effect: {} = 0x{}", override.key(),
                    Integer.toHexString(override.packetId()));
        }
    }

    private static ProtocolState parseState(ProtocolOverride override) {
        try {
            return ProtocolState.valueOf(override.state().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("protocol override '" + override.key() + "' has unknown state '"
                    + override.state() + "'");
        }
    }

    private static PacketDirection parseDirection(ProtocolOverride override) {
        try {
            return PacketDirection.valueOf(override.direction().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("protocol override '" + override.key() + "' has unknown direction '"
                    + override.direction() + "'; expected clientbound or serverbound");
        }
    }
}
