package dev.relay.net;

import dev.relay.protocol.Packet;
import dev.relay.protocol.packet.HandshakePacket;
import dev.relay.protocol.packet.PluginMessagePacket;
import dev.relay.protocol.packet.config.ConfigDisconnectPacket;
import dev.relay.protocol.packet.config.FinishConfigurationAckPacket;
import dev.relay.protocol.packet.config.FinishConfigurationPacket;
import dev.relay.protocol.packet.login.EncryptionRequestPacket;
import dev.relay.protocol.packet.login.EncryptionResponsePacket;
import dev.relay.protocol.packet.login.LoginAcknowledgedPacket;
import dev.relay.protocol.packet.login.LoginDisconnectPacket;
import dev.relay.protocol.packet.login.LoginPluginRequestPacket;
import dev.relay.protocol.packet.login.LoginPluginResponsePacket;
import dev.relay.protocol.packet.login.LoginStartPacket;
import dev.relay.protocol.packet.login.LoginSuccessPacket;
import dev.relay.protocol.packet.login.SetCompressionPacket;
import dev.relay.protocol.packet.play.ChatCommandPacket;
import dev.relay.protocol.packet.play.ConfigurationAcknowledgedPacket;
import dev.relay.protocol.packet.play.PlayDisconnectPacket;
import dev.relay.protocol.packet.play.StartConfigurationPacket;
import dev.relay.protocol.packet.play.SystemChatPacket;
import dev.relay.protocol.packet.status.StatusPingPacket;
import dev.relay.protocol.packet.status.StatusRequestPacket;
import dev.relay.protocol.packet.status.StatusResponsePacket;
import io.netty.buffer.ByteBuf;

/**
 * Per-state behaviour for one {@link MinecraftConnection}.
 *
 * <p>Every {@code handle} method returns {@code false} by default, meaning "I did not
 * consume this &mdash; forward it." Handlers override only the packets they care about,
 * which keeps each state's logic to the few packets that actually matter to it.
 */
public interface SessionHandler {

    /** Called once when this handler becomes the connection's active handler. */
    default void activated() {
    }

    /** Called when this handler is replaced or the connection closes. */
    default void deactivated() {
    }

    /**
     * Receives frames Relay has no registered packet for &mdash; the overwhelming
     * majority of play traffic. The buffer is owned by the caller; retain it if you
     * keep it past the call.
     */
    default void handleUnknown(ByteBuf frame) {
    }

    /**
     * Receives packets Relay decoded but this handler chose not to consume, i.e. those
     * whose {@code handle} overload returned {@code false}.
     */
    default void handleUnhandled(Packet packet) {
    }

    /** Called after the peer's TCP connection drops. */
    default void disconnected() {
    }

    default void exception(Throwable cause) {
    }

    default void writabilityChanged() {
    }

    default boolean handle(HandshakePacket packet) {
        return false;
    }

    default boolean handle(StatusRequestPacket packet) {
        return false;
    }

    default boolean handle(StatusPingPacket packet) {
        return false;
    }

    default boolean handle(StatusResponsePacket packet) {
        return false;
    }

    default boolean handle(LoginStartPacket packet) {
        return false;
    }

    default boolean handle(EncryptionRequestPacket packet) {
        return false;
    }

    default boolean handle(EncryptionResponsePacket packet) {
        return false;
    }

    default boolean handle(LoginSuccessPacket packet) {
        return false;
    }

    default boolean handle(SetCompressionPacket packet) {
        return false;
    }

    default boolean handle(LoginDisconnectPacket packet) {
        return false;
    }

    default boolean handle(LoginAcknowledgedPacket packet) {
        return false;
    }

    default boolean handle(LoginPluginRequestPacket packet) {
        return false;
    }

    default boolean handle(LoginPluginResponsePacket packet) {
        return false;
    }

    default boolean handle(FinishConfigurationPacket packet) {
        return false;
    }

    default boolean handle(FinishConfigurationAckPacket packet) {
        return false;
    }

    default boolean handle(ConfigDisconnectPacket packet) {
        return false;
    }

    default boolean handle(PluginMessagePacket packet) {
        return false;
    }

    default boolean handle(PlayDisconnectPacket packet) {
        return false;
    }

    default boolean handle(StartConfigurationPacket packet) {
        return false;
    }

    default boolean handle(ConfigurationAcknowledgedPacket packet) {
        return false;
    }

    default boolean handle(ChatCommandPacket packet) {
        return false;
    }

    default boolean handle(SystemChatPacket packet) {
        return false;
    }
}
