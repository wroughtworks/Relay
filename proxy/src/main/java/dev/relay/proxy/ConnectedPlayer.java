package dev.relay.proxy;

import dev.relay.auth.GameProfile;
import dev.relay.net.MinecraftConnection;
import dev.relay.protocol.ProtocolState;
import dev.relay.protocol.ProtocolVersion;
import dev.relay.protocol.packet.config.ConfigDisconnectPacket;
import dev.relay.protocol.packet.login.LoginDisconnectPacket;
import dev.relay.protocol.packet.play.PlayDisconnectPacket;
import dev.relay.protocol.packet.play.SystemChatPacket;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** A player, their socket, and whichever backend they are currently talking to. */
public final class ConnectedPlayer {

    private static final Logger LOG = LoggerFactory.getLogger(ConnectedPlayer.class);

    private final MinecraftConnection connection;
    private final GameProfile profile;
    private final ProtocolVersion version;
    private final String virtualHost;
    private final long connectedAt = System.currentTimeMillis();

    /**
     * Spec &sect;7.7's route id, issued once per session.
     *
     * <p>Its stated job is loop prevention across chained proxies, which is a v2 concern.
     * Issuing it now costs eight characters and makes a session greppable: one token ties
     * every log line, event and dashboard row for one visit together, which is the thing
     * that is missing at three in the morning.
     */
    private final String routeId = UUID.randomUUID().toString().substring(0, 8);

    private final AtomicReference<ServerConnection> connectedServer = new AtomicReference<>();
    private final AtomicReference<ServerConnection> connectionInFlight = new AtomicReference<>();

    /** Held while a chain of fallback candidates is being walked for this player. */
    private final AtomicBoolean recovering = new AtomicBoolean();

    private long lastFallbackAt;
    private int consecutiveFallbacks;

    /**
     * The last backend this player actually entered play on.
     *
     * <p>Distinct from {@link #connectedServer()}, which is null for the length of a
     * switch. Something has to remember where they came from across that gap, or an
     * arrival cannot be told from a first join and a disconnect cannot say where it
     * happened.
     */
    private volatile RegisteredServer lastArrival;

    public ConnectedPlayer(MinecraftConnection connection, GameProfile profile, ProtocolVersion version,
                           String virtualHost) {
        this.connection = connection;
        this.profile = profile;
        this.version = version;
        this.virtualHost = virtualHost;
    }

    public MinecraftConnection connection() {
        return connection;
    }

    public GameProfile profile() {
        return profile;
    }

    public UUID uuid() {
        return profile.uuid();
    }

    public String username() {
        return profile.name();
    }

    public ProtocolVersion version() {
        return version;
    }

    /** The hostname the player typed, before any forwarding data was appended. */
    public String virtualHost() {
        return virtualHost;
    }

    public long connectedAt() {
        return connectedAt;
    }

    public String routeId() {
        return routeId;
    }

    /**
     * The upstream proxy this player arrived through, or {@code null} if they came direct.
     *
     * <p>Known only from a PROXY protocol header. Without one, anything in front of Relay
     * is invisible to it by design.
     */
    public InetSocketAddress proxiedFrom() {
        SocketAddress upstream = connection.upstreamAddress();
        return upstream instanceof InetSocketAddress inet ? inet : null;
    }

    public SocketAddress remoteAddress() {
        return connection.remoteAddress();
    }

    /** The player's IP without a port, as forwarding schemes expect it. */
    public String remoteIp() {
        SocketAddress address = connection.remoteAddress();
        if (address instanceof InetSocketAddress inet) {
            return inet.getAddress() == null ? inet.getHostString() : inet.getAddress().getHostAddress();
        }
        return address.toString();
    }

    public ServerConnection connectedServer() {
        return connectedServer.get();
    }

    public void setConnectedServer(ServerConnection server) {
        connectedServer.set(server);
    }

    public ServerConnection connectionInFlight() {
        return connectionInFlight.get();
    }

    /**
     * Claims the pending-switch slot.
     *
     * @return {@code false} if a switch is already under way, in which case the caller
     *         must abandon its attempt &mdash; two concurrent switches would race to
     *         reconfigure the same client
     */
    public boolean beginConnect(ServerConnection attempt) {
        return connectionInFlight.compareAndSet(null, attempt);
    }

    public void endConnect(ServerConnection attempt) {
        connectionInFlight.compareAndSet(attempt, null);
    }

    /**
     * Claims the right to walk a list of servers looking for one that will take this
     * player.
     *
     * <p>Exactly one chain may run at a time. Both the initial join and a rescue after a
     * backend dies work by trying candidates in order, and a second chain starting
     * underneath the first would have two attempts racing to configure one client
     * &mdash; the same hazard {@link #beginConnect} guards, one level up.
     *
     * @return {@code false} if a chain is already running, in which case the caller must
     *         leave the player to it
     */
    public boolean beginRecovery() {
        return recovering.compareAndSet(false, true);
    }

    public void endRecovery() {
        recovering.set(false);
    }

    public boolean isRecovering() {
        return recovering.get();
    }

    /**
     * Counts a rescue, treating ones close together as a single flapping episode.
     *
     * <p>Two backends that both accept a player and then drop them would otherwise pass
     * them back and forth forever. Spacing is what separates that from ordinary
     * operation: a server going down twice inside the window is already abnormal,
     * whereas one going down twice in an evening should get a full set of retries each
     * time.
     *
     * @return how many rescues have now happened in a row within {@code windowMillis}
     */
    public int recordFallback(long windowMillis) {
        long now = System.currentTimeMillis();
        consecutiveFallbacks = now - lastFallbackAt <= windowMillis ? consecutiveFallbacks + 1 : 1;
        lastFallbackAt = now;
        return consecutiveFallbacks;
    }

    public RegisteredServer lastArrival() {
        return lastArrival;
    }

    public void setLastArrival(RegisteredServer server) {
        this.lastArrival = server;
    }

    public boolean isActive() {
        return connection.isActive();
    }

    public void sendMessage(Component message) {
        if (connection.state() == ProtocolState.PLAY) {
            connection.write(SystemChatPacket.chat(message));
        }
    }

    /**
     * Kicks the player, choosing the right disconnect packet for their current state.
     *
     * <p>Each state has its own packet id and, from 1.20.3, its own component encoding.
     * Getting this wrong would replace a readable reason with a generic "connection
     * lost" — which is exactly the message that is least useful when a backend is down.
     */
    public void disconnect(Component reason) {
        // Logged so a Relay-initiated kick is never mistaken for the player quitting.
        // The two look identical downstream: both end with the socket closing.
        LOG.info("Disconnecting {}: {}", username(),
                LegacyComponentSerializer.legacySection().serialize(reason).replaceAll("§.", ""));
        switch (connection.state()) {
            case LOGIN -> connection.closeWith(LoginDisconnectPacket.of(reason));
            case CONFIGURATION -> connection.closeWith(ConfigDisconnectPacket.of(reason));
            case PLAY -> connection.closeWith(PlayDisconnectPacket.of(reason));
            // Handshake or status: no disconnect packet exists to say it with.
            default -> connection.close();
        }
    }

    @Override
    public String toString() {
        return username() + " (" + uuid() + ")";
    }
}
