package dev.relay.protocol;

import dev.relay.protocol.packet.HandshakePacket;
import dev.relay.protocol.packet.config.FinishConfigurationAckPacket;
import dev.relay.protocol.packet.config.FinishConfigurationPacket;
import dev.relay.protocol.packet.login.LoginStartPacket;
import dev.relay.protocol.packet.login.LoginSuccessPacket;
import dev.relay.protocol.packet.play.PlayDisconnectPacket;
import dev.relay.protocol.packet.play.StartConfigurationPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateRegistryTest {

    @Test
    void handshakeAndLoginIdsHoldAcrossTheWholeRange() {
        for (ProtocolVersion version : ProtocolVersion.values()) {
            assertEquals(0x00, StateRegistry.HANDSHAKE.serverbound.idOf(HandshakePacket.class, version),
                    "handshake id changed at " + version);
            assertEquals(0x00, StateRegistry.LOGIN.serverbound.idOf(LoginStartPacket.class, version),
                    "login start id changed at " + version);
            assertEquals(0x02, StateRegistry.LOGIN.clientbound.idOf(LoginSuccessPacket.class, version),
                    "login success id changed at " + version);
        }
    }

    /** 1.20.5 inserted cookie packets at id 0, shifting configuration state by one. */
    @Test
    void configurationIdsShiftAt1_20_5() {
        assertEquals(0x02, StateRegistry.CONFIGURATION.clientbound
                .idOf(FinishConfigurationPacket.class, ProtocolVersion.MINECRAFT_1_20_2));
        assertEquals(0x02, StateRegistry.CONFIGURATION.clientbound
                .idOf(FinishConfigurationPacket.class, ProtocolVersion.MINECRAFT_1_20_3));
        assertEquals(0x03, StateRegistry.CONFIGURATION.clientbound
                .idOf(FinishConfigurationPacket.class, ProtocolVersion.MINECRAFT_1_20_5));
        assertEquals(0x03, StateRegistry.CONFIGURATION.clientbound
                .idOf(FinishConfigurationPacket.class, ProtocolVersion.MINECRAFT_1_21_7));
    }

    @Test
    void everyRegisteredPacketHasAnIdAtEveryVersion() {
        for (ProtocolVersion version : ProtocolVersion.values()) {
            assertNotEquals(-1, StateRegistry.CONFIGURATION.serverbound
                    .idOf(FinishConfigurationAckPacket.class, version), "config ack missing at " + version);
            assertNotEquals(-1, StateRegistry.PLAY.clientbound
                    .idOf(StartConfigurationPacket.class, version), "start configuration missing at " + version);
            assertNotEquals(-1, StateRegistry.PLAY.clientbound
                    .idOf(PlayDisconnectPacket.class, version), "play disconnect missing at " + version);
        }
    }

    /**
     * Clientbound play is registered write-only so a wrong id can never break decoding of
     * backend traffic. Encoding must still work.
     */
    @Test
    void clientboundPlayPacketsEncodeButNeverDecode() {
        ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;
        int id = StateRegistry.PLAY.clientbound.idOf(StartConfigurationPacket.class, version);
        assertTrue(id > 0);
        assertNull(StateRegistry.PLAY.clientbound.create(id, version),
                "clientbound play packets must not be decodable");
    }

    @Test
    void decodableStatesProduceInstances() {
        ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_4;
        Packet packet = StateRegistry.LOGIN.serverbound.create(0x00, version);
        assertInstanceOf(LoginStartPacket.class, packet);
    }

    @Test
    void unregisteredIdsDecodeToNothing() {
        assertNull(StateRegistry.PLAY.serverbound.create(0x7E, ProtocolVersion.MINECRAFT_1_21_4));
    }

    /**
     * The config-file escape hatch for a Minecraft update that moves an id. Restored
     * afterwards because the registry is process-global.
     */
    @Test
    void overrideRetargetsAPacketId() {
        ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_7;
        int original = StateRegistry.PLAY.clientbound.idOf(StartConfigurationPacket.class, version);
        try {
            StateRegistry.override(ProtocolState.PLAY, PacketDirection.CLIENTBOUND,
                    "start_configuration", version, 0x7A);
            assertEquals(0x7A, StateRegistry.PLAY.clientbound.idOf(StartConfigurationPacket.class, version));
            // Other versions must be untouched.
            assertNotEquals(0x7A, StateRegistry.PLAY.clientbound
                    .idOf(StartConfigurationPacket.class, ProtocolVersion.MINECRAFT_1_20_2));
        } finally {
            StateRegistry.override(ProtocolState.PLAY, PacketDirection.CLIENTBOUND,
                    "start_configuration", version, original);
        }
    }

    @Test
    void overrideRemapsTheDecoderToo() {
        ProtocolVersion version = ProtocolVersion.MINECRAFT_1_21_7;
        int original = StateRegistry.PLAY.serverbound.idOf(
                dev.relay.protocol.packet.play.ChatCommandPacket.class, version);
        try {
            StateRegistry.override(ProtocolState.PLAY, PacketDirection.SERVERBOUND,
                    "chat_command", version, 0x42);
            assertInstanceOf(dev.relay.protocol.packet.play.ChatCommandPacket.class,
                    StateRegistry.PLAY.serverbound.create(0x42, version));
            assertNull(StateRegistry.PLAY.serverbound.create(original, version),
                    "the old id must stop decoding");
        } finally {
            StateRegistry.override(ProtocolState.PLAY, PacketDirection.SERVERBOUND,
                    "chat_command", version, original);
        }
    }

    @Test
    void overrideRejectsAnUnknownPacketName() {
        assertThrows(IllegalArgumentException.class, () -> StateRegistry.override(
                ProtocolState.PLAY, PacketDirection.CLIENTBOUND, "not_a_packet",
                ProtocolVersion.MINECRAFT_1_21_4, 1));
    }
}
