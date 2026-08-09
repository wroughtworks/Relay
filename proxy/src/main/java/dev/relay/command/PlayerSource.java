package dev.relay.command;

import dev.relay.proxy.ConnectedPlayer;
import net.kyori.adventure.text.Component;

/** A {@link CommandSource} backed by a connected player. */
public record PlayerSource(ConnectedPlayer player, Permissions permissions) implements CommandSource {

    @Override
    public String name() {
        return player.username();
    }

    @Override
    public void sendMessage(Component message) {
        player.sendMessage(message);
    }

    @Override
    public boolean hasPermission(String node) {
        return permissions.has(player.username(), player.uuid(), node);
    }

    @Override
    public ConnectedPlayer asPlayer() {
        return player;
    }
}
