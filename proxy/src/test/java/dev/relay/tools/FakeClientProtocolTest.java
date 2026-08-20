package dev.relay.tools;

import dev.relay.protocol.ProtocolVersion;
import dev.relay.protocol.StateRegistry;
import dev.relay.protocol.packet.config.ConfigDisconnectPacket;
import dev.relay.protocol.packet.config.FinishConfigurationAckPacket;
import dev.relay.protocol.packet.config.FinishConfigurationPacket;
import dev.relay.protocol.packet.play.ChatCommandPacket;
import dev.relay.protocol.packet.play.ConfigurationAcknowledgedPacket;
import dev.relay.protocol.packet.play.StartConfigurationPacket;
import dev.relay.tools.FakePlayers.Ids;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The fake client's packet ids, checked against Relay's.
 *
 * <p>Two hand-maintained tables describe the same protocol: {@link StateRegistry}, which is
 * what Relay speaks, and {@code FakePlayers.IDS}, which is what the load tool speaks. They
 * are kept separate on purpose &mdash; a fake client that read its ids out of the proxy it
 * is testing would agree with that proxy by construction, and a version where Relay's table
 * was wrong would sail through with every player connecting happily.
 *
 * <p>Separate tables only help if somebody compares them, which is this test. A
 * disagreement means one of the two is wrong, and which one is a question for the protocol
 * reference; either way it is a real finding rather than a merge artefact.
 *
 * <p>Only the overlap is checked. The tool needs several ids Relay has no opinion about
 * &mdash; keep-alive, the join teleport and its confirmation, client information &mdash;
 * because Relay forwards those as opaque frames and never decodes them. Those have no
 * second source here and rest on the live-server observations recorded in
 * {@code docs/protocol-ids.md}.
 */
class FakeClientProtocolTest {

    @Test
    void theToolAndTheProxyAgreeOnEveryVersionTheToolClaims() {
        for (Map.Entry<Integer, Ids> entry : FakePlayers.IDS.entrySet()) {
            ProtocolVersion version = ProtocolVersion.byId(entry.getKey());
            Ids ids = entry.getValue();
            assertNotNull(version, "the tool claims protocol " + entry.getKey()
                    + ", which Relay does not support at all");

            assertEquals(
                    StateRegistry.CONFIGURATION.serverbound.idOf(FinishConfigurationAckPacket.class, version),
                    ids.configFinishAck(),
                    () -> mismatch(ids, "configuration finish acknowledgement"));
            assertEquals(
                    StateRegistry.CONFIGURATION.clientbound.idOf(FinishConfigurationPacket.class, version),
                    ids.configFinish(),
                    () -> mismatch(ids, "finish configuration"));
            assertEquals(
                    StateRegistry.CONFIGURATION.clientbound.idOf(ConfigDisconnectPacket.class, version),
                    ids.configDisconnect(),
                    () -> mismatch(ids, "configuration disconnect"));
            assertEquals(
                    StateRegistry.PLAY.serverbound.idOf(ConfigurationAcknowledgedPacket.class, version),
                    ids.playConfigAck(),
                    () -> mismatch(ids, "configuration acknowledged"));
            assertEquals(
                    StateRegistry.PLAY.serverbound.idOf(ChatCommandPacket.class, version),
                    ids.playChatCommand(),
                    () -> mismatch(ids, "chat command"));
            assertEquals(
                    StateRegistry.PLAY.clientbound.idOf(StartConfigurationPacket.class, version),
                    ids.startConfiguration(),
                    () -> mismatch(ids, "start configuration"));
        }
    }

    /**
     * The floor is present, so the tool is never silently unable to test anything.
     *
     * <p>A load tool whose table is empty still starts, still prints a distribution, and
     * still says nothing &mdash; which is the failure mode worth a test of its own.
     */
    @Test
    void theOldestSupportedVersionIsAlwaysDriveable() {
        assertFalse(FakePlayers.IDS.isEmpty(), "the fake client can no longer speak any version");
        assertNotNull(FakePlayers.IDS.get(ProtocolVersion.oldest().id()),
                "the fake client cannot speak " + ProtocolVersion.oldest().displayName()
                        + ", which is the version every other test and every live check uses");
    }

    private static String mismatch(Ids ids, String packet) {
        return "the fake client and Relay disagree about " + packet + " at " + ids.version()
                + ". One of the two tables is wrong: check it against the protocol reference "
                + "and docs/protocol-ids.md rather than making them match.";
    }
}
