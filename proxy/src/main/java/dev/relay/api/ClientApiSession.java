package dev.relay.api;

import dev.relay.command.Permissions;
import dev.relay.protocol.ProtocolException;
import dev.relay.protocol.ProtocolUtils;
import dev.relay.proxy.ConnectedPlayer;
import dev.relay.proxy.ConnectionResult;
import dev.relay.proxy.RegisteredServer;
import dev.relay.proxy.RelayProxy;
import dev.relay.proxy.ServerConnection;
import dev.relay.session.BackendConnector;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static dev.relay.api.ClientApi.Capability;
import static dev.relay.api.ClientApi.Clientbound;
import static dev.relay.api.ClientApi.Error;
import static dev.relay.api.ClientApi.Serverbound;

/**
 * One modded client's conversation with the proxy.
 *
 * <p>State per player, created on first contact and discarded with the connection. Every
 * inbound message is treated as hostile input: bounded in size, rate limited, parsed
 * defensively, and permission-checked at the point of use rather than at the point the
 * capability was advertised.
 */
public final class ClientApiSession {

    private static final Logger LOG = LoggerFactory.getLogger(ClientApiSession.class);

    /** Bound on the mod name a client may report, which is only ever used for logging. */
    private static final int MAX_MOD_NAME_LENGTH = 64;

    private final RelayProxy proxy;
    private final ConnectedPlayer player;

    private boolean helloReceived;
    private int negotiatedVersion;
    private long windowStartedAt = System.currentTimeMillis();
    private int messagesThisWindow;

    public ClientApiSession(RelayProxy proxy, ConnectedPlayer player) {
        this.proxy = proxy;
        this.player = player;
    }

    /** True once the client has identified itself and may issue requests. */
    public boolean established() {
        return helloReceived;
    }

    /**
     * Handles one message from the client.
     *
     * @return {@code false} if the connection should be dropped, which is reserved for
     *         input no legitimate mod produces
     */
    public boolean handle(byte[] data) {
        if (data.length > ClientApi.MAX_MESSAGE_BYTES) {
            LOG.warn("{} sent a {} byte client API message, over the {} byte limit; dropping the connection",
                    player.username(), data.length, ClientApi.MAX_MESSAGE_BYTES);
            return false;
        }
        if (!allowedByRateLimit()) {
            // Refuse the message but keep the session: a burst is far more likely to be
            // an enthusiastic mod than an attack, and the cap already bounds the damage.
            sendError(Error.RATE_LIMITED);
            return true;
        }

        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            int type = ProtocolUtils.readVarInt(buf);
            if (!helloReceived && type != Serverbound.HELLO) {
                // Nothing is answered before the version is agreed, or Relay would be
                // replying in a format the client has not confirmed it understands.
                sendError(Error.MALFORMED);
                return true;
            }
            switch (type) {
                case Serverbound.HELLO -> handleHello(buf);
                case Serverbound.LIST_SERVERS -> handleListServers();
                case Serverbound.REQUEST_SWITCH -> handleRequestSwitch(buf);
                // An unknown type is a newer mod talking to an older proxy, which
                // negotiation is supposed to prevent but should not be fatal.
                default -> sendError(Error.MALFORMED);
            }
            return true;
        } catch (ProtocolException e) {
            LOG.debug("Malformed client API message from {}: {}", player.username(), e.getMessage());
            sendError(Error.MALFORMED);
            return true;
        } finally {
            buf.release();
        }
    }

    private boolean allowedByRateLimit() {
        long now = System.currentTimeMillis();
        if (now - windowStartedAt >= 1000) {
            windowStartedAt = now;
            messagesThisWindow = 0;
        }
        return ++messagesThisWindow <= ClientApi.MAX_MESSAGES_PER_SECOND;
    }

    // ------------------------------------------------------------ handlers

    private void handleHello(ByteBuf buf) {
        int clientVersion = ProtocolUtils.readVarInt(buf);
        String modName = ProtocolUtils.readString(buf, MAX_MOD_NAME_LENGTH);

        if (clientVersion < 1) {
            sendError(Error.MALFORMED);
            return;
        }
        // Meet in the middle so either side can be upgraded without the other.
        negotiatedVersion = Math.min(clientVersion, ClientApi.PROTOCOL_VERSION);
        helloReceived = true;

        LOG.info("{} connected with client mod '{}' (its API v{}, using v{})",
                player.username(), modName, clientVersion, negotiatedVersion);

        List<String> capabilities = permittedCapabilities();
        ByteBuf response = Unpooled.buffer();
        ProtocolUtils.writeVarInt(response, Clientbound.WELCOME);
        ProtocolUtils.writeVarInt(response, negotiatedVersion);
        ProtocolUtils.writeString(response, "Relay " + RelayProxy.version());
        ProtocolUtils.writeVarInt(response, capabilities.size());
        for (String capability : capabilities) {
            ProtocolUtils.writeString(response, capability);
        }
        send(response);
    }

    /**
     * The capabilities this player may actually use.
     *
     * <p>Filtered by permission so a mod can render a menu that matches what the player
     * can do. This is a convenience for the client, never a substitute for the check at
     * the point of use.
     */
    private List<String> permittedCapabilities() {
        Permissions permissions = proxy.commands().permissions();
        List<String> capabilities = new ArrayList<>(2);
        if (permissions.has(player.username(), player.uuid(), "relay.command.glist")) {
            capabilities.add(Capability.SERVER_LIST);
        }
        if (permissions.has(player.username(), player.uuid(), "relay.command.server")) {
            capabilities.add(Capability.SWITCH);
        }
        return capabilities;
    }

    private void handleListServers() {
        if (!hasPermission("relay.command.glist")) {
            sendError(Error.NO_PERMISSION);
            return;
        }

        ServerConnection current = player.connectedServer();
        List<RegisteredServer> servers = List.copyOf(proxy.servers());

        ByteBuf response = Unpooled.buffer();
        ProtocolUtils.writeVarInt(response, Clientbound.SERVER_LIST);
        ProtocolUtils.writeVarInt(response, servers.size());
        for (RegisteredServer server : servers) {
            ProtocolUtils.writeString(response, server.name());
            ProtocolUtils.writeVarInt(response, server.playerCount());
            response.writeBoolean(current != null && current.target() == server);
        }
        send(response);
    }

    private void handleRequestSwitch(ByteBuf buf) {
        String target = ProtocolUtils.readString(buf, 64);

        // Re-checked here rather than relying on the advertised capability: permissions
        // can change mid-session, and a client can send this whatever it was told.
        if (!hasPermission("relay.command.server")) {
            sendError(Error.NO_PERMISSION);
            return;
        }

        RegisteredServer server = proxy.select(target).orElse(null);
        if (server == null) {
            sendError(Error.UNKNOWN_SERVER);
            return;
        }
        ServerConnection current = player.connectedServer();
        if (current != null && current.target() == server) {
            sendError(Error.ALREADY_CONNECTED);
            return;
        }
        if (player.connectionInFlight() != null) {
            sendError(Error.SWITCH_IN_PROGRESS);
            return;
        }

        sendSwitchStatus(server.name(), SwitchStatus.CONNECTING, "");
        new BackendConnector(proxy, player).connect(server).whenComplete((result, error) ->
                player.connection().channel().eventLoop().execute(() -> {
                    if (!player.isActive()) {
                        return;
                    }
                    if (error != null || result == null) {
                        sendSwitchStatus(server.name(), SwitchStatus.FAILED, "Internal error");
                        return;
                    }
                    switch (result) {
                        case ConnectionResult.Success ignored ->
                                sendSwitchStatus(server.name(), SwitchStatus.CONNECTED, "");
                        case ConnectionResult.Rejected rejected -> sendSwitchStatus(server.name(),
                                SwitchStatus.FAILED, BackendConnector.plain(rejected.reason()));
                        case ConnectionResult.Unreachable ignored -> sendSwitchStatus(server.name(),
                                SwitchStatus.FAILED, server.name() + " is not reachable");
                    }
                }));
    }

    /** Progress values for {@link Clientbound#SWITCH_STATUS}. */
    private enum SwitchStatus {
        CONNECTING,
        CONNECTED,
        FAILED
    }

    private void sendSwitchStatus(String server, SwitchStatus status, String detail) {
        ByteBuf response = Unpooled.buffer();
        ProtocolUtils.writeVarInt(response, Clientbound.SWITCH_STATUS);
        ProtocolUtils.writeString(response, server);
        ProtocolUtils.writeVarInt(response, status.ordinal());
        ProtocolUtils.writeString(response, detail);
        send(response);
    }

    private void sendError(int code) {
        ByteBuf response = Unpooled.buffer();
        ProtocolUtils.writeVarInt(response, Clientbound.ERROR);
        ProtocolUtils.writeVarInt(response, code);
        send(response);
    }

    private boolean hasPermission(String node) {
        return proxy.commands().permissions().has(player.username(), player.uuid(), node);
    }

    private void send(ByteBuf payload) {
        try {
            byte[] data = new byte[payload.readableBytes()];
            payload.readBytes(data);
            player.connection().write(
                    new dev.relay.protocol.packet.PluginMessagePacket(ClientApi.CHANNEL, data));
        } finally {
            payload.release();
        }
    }
}
