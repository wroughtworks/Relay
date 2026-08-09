package dev.relay.session;

import dev.relay.net.MinecraftConnection;
import dev.relay.net.SessionHandler;
import dev.relay.protocol.packet.status.StatusPingPacket;
import dev.relay.protocol.packet.status.StatusRequestPacket;
import dev.relay.protocol.packet.status.StatusResponsePacket;
import dev.relay.proxy.RelayProxy;

/**
 * Serves the server-list ping.
 *
 * <p>Answered entirely from proxy state &mdash; no backend is contacted &mdash; so the
 * list still renders when every backend is down, and a ping flood cannot be turned into
 * traffic against them.
 */
public final class StatusSessionHandler implements SessionHandler {

    private final RelayProxy proxy;
    private final MinecraftConnection connection;
    private final int clientProtocol;
    private boolean answered;

    public StatusSessionHandler(RelayProxy proxy, MinecraftConnection connection, int clientProtocol) {
        this.proxy = proxy;
        this.connection = connection;
        this.clientProtocol = clientProtocol;
    }

    @Override
    public boolean handle(StatusRequestPacket packet) {
        if (answered) {
            // One request per connection; repeats are a client bug or a probe.
            connection.close();
            return true;
        }
        answered = true;
        connection.write(new StatusResponsePacket(ServerPing.create(proxy, clientProtocol)));
        return true;
    }

    @Override
    public boolean handle(StatusPingPacket packet) {
        // Echo the token, then close: the ping is the last exchange in status state.
        connection.closeWith(new StatusPingPacket(packet.token()));
        return true;
    }
}
