package dev.relay.proxy;

import dev.relay.auth.SessionAuthenticator;
import dev.relay.command.CommandManager;
import dev.relay.config.ForwardingMode;
import dev.relay.config.RelayConfig;
import dev.relay.config.RelayConfig.ProtocolOverride;
import dev.relay.config.RelayConfig.ServerEntry;
import dev.relay.net.ConnectionInitializer;
import dev.relay.net.Encryption;
import dev.relay.net.MinecraftConnection;
import dev.relay.net.Transport;
import dev.relay.companion.CompanionSupervisor;
import dev.relay.control.ControlServer;
import dev.relay.forwarding.ModernForwarding;
import dev.relay.health.HealthChecker;
import dev.relay.metrics.Metrics;
import dev.relay.store.Database;
import dev.relay.store.History;
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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
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
    /** Spec 10's counters. Player count is read from the registry rather than mirrored. */
    private final Metrics metrics = new Metrics(() -> players.count());
    private HealthChecker healthChecker;
    private Database database;
    private History history;
    private Path pidFile;
    private ControlServer control;
    private CompanionSupervisor companions;
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
        // A drained backend is announced the instant its last player leaves, rather than
        // up to a health-check interval later: that moment is the one an operator is
        // actually waiting for before restarting it.
        events.addListener(event -> {
            dev.relay.command.commands.DrainCommand.announceIfDrained(event.from());
            dev.relay.command.commands.DrainCommand.announceIfDrained(event.to());
        });
        applyProtocolOverrides();
    }

    // ------------------------------------------------------------ accessors

    public RelayConfig config() {
        return config;
    }

    public PlayerRegistry players() {
        return players;
    }

    /** @return the history recorder, or null when storage is off */
    public History history() {
        return history;
    }

    public Metrics metrics() {
        return metrics;
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
                                config.traceCloses() ? "player " + channel.remoteAddress() : null,
                                metrics);
                        // Counted here rather than at login: a connection that never gets
                        // past the handshake still consumed a socket, and the gap between
                        // this and the player count is exactly what a scan looks like.
                        metrics.connectionOpened();
                        channel.closeFuture().addListener(closed -> metrics.connectionClosed());
                        connection.setSessionHandler(new HandshakeSessionHandler(RelayProxy.this, connection));
                    }
                });

        InetSocketAddress bind = config.bind();
        ChannelFuture future = bootstrap.bind(bind.getHostString(), bind.getPort()).sync();
        listener = future.channel();

        // Deliberately verbose. Every backend-side problem so far has come down to which
        // build is running, which config it read, or which secret that config held --
        // none of which were visible from the logs while diagnosing them.
        LOG.info("Relay {} starting as node '{}'", version(), config.nodeName());
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

        // Switches are counted off the event stream rather than at each site that
        // performs one. There are three such sites and they will not stay at three;
        // a listener cannot fall out of step with them.
        events.addListener(event -> {
            if (event.kind() == ProxyEvents.Kind.PLAYER_SWITCHED_SERVER) {
                metrics.switched();
            }
        });
        if (config.storageEnabled()) {
            java.nio.file.Path databaseFile = config.sourcePath().getParent() == null
                    ? java.nio.file.Path.of(config.storageFile())
                    : config.sourcePath().getParent().resolve(config.storageFile());
            database = new Database(databaseFile);
            try {
                database.start();
                history = new History(database, config.storageRetainDays());
                events.addListener(history.listener());
                history.startPruning();
                metrics.persistTo(database);
            } catch (java.sql.SQLException e) {
                // Not fatal. A proxy that refuses to carry players because a history
                // table would not open has traded the job for the paperwork.
                LOG.warn("Storage is unavailable, so nothing will be recorded: {}", e.getMessage());
                database = null;
                history = null;
            }
        }
        metrics.start();
        if (config.healthEnabled()) {
            healthChecker = new HealthChecker(this);
            healthChecker.start();
        }

        // Last, and after the player listener is already open, so nothing here can stop
        // the proxy doing its actual job. Companions start only once the control channel
        // is bound, since the port and token are handed to them through their environment.
        writePidFile();

        if (config.controlEnabled()) {
            startCompanions();
        } else if (!config.companions().isEmpty()) {
            LOG.warn("{} companion(s) are configured but control.enabled is false. They have no way "
                            + "to reach the proxy, so none will be started.",
                    config.companions().size());
        }

        if (config.proxyProtocolSend()) {
            LOG.info("Sending PROXY protocol headers to backends. Each backend must accept them "
                    + "(proxies.proxy-protocol: true in paper-global.yml), or it will reject the connection.");
        } else {
            LOG.info("Not sending PROXY protocol headers. A backend with proxies.proxy-protocol: true will "
                    + "accept the connection and then never answer; set proxy-protocol-send = true for those.");
        }
    }

    /**
     * Records this process's id beside the config, and removes it on the way out.
     *
     * <p>For the build, which uses it to warn when the jar is being replaced under a
     * running proxy. That replacement does not fail anything at the time: the JVM has
     * already loaded what it is using, and it breaks much later, when it first needs a
     * class it had not loaded yet, as a {@code NoClassDefFoundError} deep inside Netty.
     * Nothing in that stack trace mentions the jar, so it reads as a Relay bug. It has
     * cost this project a debugging session twice.
     *
     * <p>A file rather than letting the build scan processes: {@code commandLine()} is
     * empty for other processes on Windows, so a scan silently finds nothing on the one
     * platform where this project actually runs.
     */
    private void writePidFile() {
        try {
            Path directory = config.sourcePath().getParent().resolve(".relay-run");
            Files.createDirectories(directory);
            pidFile = directory.resolve("relay.pid");
            Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()));
            pidFile.toFile().deleteOnExit();
        } catch (IOException e) {
            // A convenience for tooling, not something to refuse to start over.
            LOG.debug("Could not write the pid file", e);
            pidFile = null;
        }
    }

    /**
     * Opens the control channel and starts whatever is configured to talk on it.
     *
     * <p>A failure here is logged and left there. The dashboard not coming up is a
     * nuisance; a proxy that refuses to serve players because its status page would not
     * start has its priorities backwards.
     */
    private void startCompanions() {
        try {
            control = new ControlServer(this);
            control.start(workerGroup, config.controlBind());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            // Exception, not RuntimeException. A port already in use surfaces as a
            // BindException, which Netty rethrows unchecked from sync() -- so it is a
            // checked exception arriving where the compiler cannot see it, and catching
            // only RuntimeException let it escape and abort startup. The proxy would
            // then refuse to serve players because its status page could not bind.
            LOG.error("The control channel could not be opened on {}: {}. Companions will not start, "
                            + "and the proxy continues without them.",
                    config.controlBind(), e.toString());
            control = null;
            return;
        }
        if (!config.companions().isEmpty()) {
            companions = new CompanionSupervisor(config.companions(), control,
                    config.sourcePath().getParent());
            companions.start();
        }
    }

    /** @return the control channel, or {@code null} if it is disabled or failed to open */
    public ControlServer control() {
        return control;
    }

    /** The transport in use, for anything that needs to open a listener of its own. */
    public Transport transport() {
        return transport;
    }

    /** Idempotent: the shutdown hook and an explicit {@code stop} both land here. */
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }
        LOG.info("Shutting down");
        if (pidFile != null) {
            try {
                Files.deleteIfExists(pidFile);
            } catch (IOException ignored) {
                // deleteOnExit is the backstop.
            }
        }
        // Companions are told over the control channel and given a moment, before the
        // channel itself closes underneath them.
        if (companions != null) {
            companions.stop();
        }
        if (control != null) {
            control.stop();
        }
        metrics.stop();
        if (history != null) {
            history.stopPruning();
            // Before the database closes: a proxy that is stopped rather than killed
            // should not leave a history full of players who apparently never left.
            history.closeOpenSessions();
        }
        if (database != null) {
            database.close();
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
                                        : null,
                                metrics);
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
