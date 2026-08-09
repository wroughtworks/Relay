package dev.relay.proxy;

import dev.relay.net.MinecraftConnection;
import net.kyori.adventure.text.Component;

import java.util.concurrent.CompletableFuture;

/** Relay's side of one backend socket, on behalf of one player. */
public final class ServerConnection {

    private final RegisteredServer target;
    private final ConnectedPlayer player;
    private final CompletableFuture<ConnectionResult> result = new CompletableFuture<>();

    private volatile MinecraftConnection connection;
    private volatile boolean established;
    private volatile Runnable clientReadyCallback;

    public ServerConnection(RegisteredServer target, ConnectedPlayer player) {
        this.target = target;
        this.player = player;
    }

    public RegisteredServer target() {
        return target;
    }

    public ConnectedPlayer player() {
        return player;
    }

    public MinecraftConnection connection() {
        return connection;
    }

    public void setConnection(MinecraftConnection connection) {
        this.connection = connection;
    }

    public CompletableFuture<ConnectionResult> result() {
        return result;
    }

    /**
     * Registers what to run once the player's own connection has re-entered
     * configuration state and is ready to receive this backend's setup.
     *
     * <p>A callback rather than a direct reference so the proxy model stays independent
     * of the session handlers that drive it.
     */
    public void setClientReadyCallback(Runnable callback) {
        this.clientReadyCallback = callback;
    }

    public void notifyClientReady() {
        Runnable callback = clientReadyCallback;
        if (callback != null) {
            clientReadyCallback = null;
            callback.run();
        }
    }

    /** True once the player is actually playing on this backend, not merely connected. */
    public boolean established() {
        return established;
    }

    public void markEstablished() {
        this.established = true;
        target.addPlayer(player);
        result.complete(new ConnectionResult.Success(target));
    }

    public void markRejected(Component reason) {
        result.complete(new ConnectionResult.Rejected(target, reason));
    }

    public void markUnreachable(Throwable cause) {
        result.complete(new ConnectionResult.Unreachable(target, cause));
    }

    public void disconnect() {
        if (established) {
            target.removePlayer(player);
            established = false;
        }
        MinecraftConnection current = connection;
        if (current != null) {
            current.close();
        }
    }

    public boolean isActive() {
        MinecraftConnection current = connection;
        return current != null && current.isActive();
    }

    @Override
    public String toString() {
        return player.username() + " -> " + target.name();
    }
}
