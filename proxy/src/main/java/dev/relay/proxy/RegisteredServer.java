package dev.relay.proxy;

import dev.relay.config.RelayConfig.ServerEntry;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** A configured backend, and the players currently on it. */
public final class RegisteredServer {

    private final String name;
    private final InetSocketAddress address;
    private final Set<ConnectedPlayer> players = ConcurrentHashMap.newKeySet();

    public RegisteredServer(ServerEntry entry) {
        this.name = entry.name();
        this.address = entry.address();
    }

    public String name() {
        return name;
    }

    public InetSocketAddress address() {
        return address;
    }

    public Collection<ConnectedPlayer> players() {
        return Collections.unmodifiableSet(players);
    }

    public int playerCount() {
        return players.size();
    }

    void addPlayer(ConnectedPlayer player) {
        players.add(player);
    }

    void removePlayer(ConnectedPlayer player) {
        players.remove(player);
    }

    @Override
    public String toString() {
        return name + " (" + address.getHostString() + ":" + address.getPort() + ")";
    }
}
