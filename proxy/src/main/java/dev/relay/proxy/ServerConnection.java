package dev.relay.proxy;

import dev.relay.net.MinecraftConnection;
import net.kyori.adventure.text.Component;

import java.util.concurrent.CompletableFuture;

/** Relay's side of one backend socket, on behalf of one player. */
public final class ServerConnection {

    /**
     * How long a detached backend gets to notice and close its own side.
     *
     * <p>Long enough for a local server to see end of stream and react, short enough that
     * a backend which ignores it is not left half-open for any length of time.
     */
    private static final long DETACH_LINGER_MILLIS = 1000;

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
            // Gracefully, because a backend being switched away from is still mid-write.
            // A plain close resets the socket and makes it log a stack trace for what is
            // a completely ordinary event.
            current.closeGracefully(DETACH_LINGER_MILLIS);
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
