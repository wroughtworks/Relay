package dev.relay.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.relay.protocol.packet.PluginMessagePacket;
import dev.relay.protocol.packet.config.ConfigDisconnectPacket;
import dev.relay.protocol.packet.config.FinishConfigurationAckPacket;
import dev.relay.protocol.packet.config.FinishConfigurationPacket;
import dev.relay.protocol.packet.play.ChatCommandPacket;
import dev.relay.protocol.packet.play.ConfigurationAcknowledgedPacket;
import dev.relay.protocol.packet.play.PlayDisconnectPacket;
import dev.relay.protocol.packet.play.StartConfigurationPacket;
import dev.relay.protocol.packet.play.SystemChatPacket;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Relay's packet ids, checked against Mojang's own report.
 *
 * <p>Every id in {@link StateRegistry} outside 1.20.2 was written from the protocol layout
 * and inferred forward &mdash; a fact {@code docs/protocol-ids.md} has stated plainly since
 * the table was written. Inference is a reasonable way to get a table started and a
 * terrible way to keep one, and this project has already been bitten twice: {@code
 * system_chat} was wrong at 1.20.2, and two packets were once given the same id at two
 * versions.
 *
 * <p>The server jar settles it without a client, a network or a guess. Running
 *
 * <pre>java -DbundlerMainClass=net.minecraft.data.Main -jar &lt;server&gt;.jar --reports</pre>
 *
 * <p>produces {@code generated/reports/packets.json}: the id of every packet, from the same
 * source that defines them. That is first-party data, not a community table, and it does
 * not drift. The files under {@code src/test/resources/protocol} are that report trimmed to
 * the packets Relay depends on, one per version, with the build they came from recorded
 * inside.
 *
 * <p>Versions with no file here are still unverified &mdash; the report provider did not
 * exist before 1.20.5, so the floor rests on a live packet log instead. Add a version by
 * generating its report and trimming it; the test picks it up with no code change.
 */
class MojangPacketReportTest {

    /** The protocol versions with a checked-in report. */
    private static final int[] VERIFIED = {767, 768, 769, 770, 771, 772};

    /** One Relay packet and the name Mojang gives it. */
    private record Expectation(String state, String direction, String mojangName,
                               StateRegistry registry, PacketDirection packetDirection,
                               Class<? extends dev.relay.protocol.Packet> type) {
    }

    private static List<Expectation> expectations() {
        return List.of(
                new Expectation("configuration", "serverbound", "minecraft:finish_configuration",
                        StateRegistry.CONFIGURATION, PacketDirection.SERVERBOUND,
                        FinishConfigurationAckPacket.class),
                new Expectation("configuration", "serverbound", "minecraft:custom_payload",
                        StateRegistry.CONFIGURATION, PacketDirection.SERVERBOUND,
                        PluginMessagePacket.class),
                new Expectation("configuration", "clientbound", "minecraft:finish_configuration",
                        StateRegistry.CONFIGURATION, PacketDirection.CLIENTBOUND,
                        FinishConfigurationPacket.class),
                new Expectation("configuration", "clientbound", "minecraft:custom_payload",
                        StateRegistry.CONFIGURATION, PacketDirection.CLIENTBOUND,
                        PluginMessagePacket.class),
                new Expectation("configuration", "clientbound", "minecraft:disconnect",
                        StateRegistry.CONFIGURATION, PacketDirection.CLIENTBOUND,
                        ConfigDisconnectPacket.class),
                // The unsigned form. From 1.20.5 Mojang splits these, and Relay registers
                // only the unsigned one -- rewriting a signed command would invalidate its
                // signature, so those pass through untouched.
                new Expectation("play", "serverbound", "minecraft:chat_command",
                        StateRegistry.PLAY, PacketDirection.SERVERBOUND,
                        ChatCommandPacket.class),
                new Expectation("play", "serverbound", "minecraft:configuration_acknowledged",
                        StateRegistry.PLAY, PacketDirection.SERVERBOUND,
                        ConfigurationAcknowledgedPacket.class),
                new Expectation("play", "serverbound", "minecraft:custom_payload",
                        StateRegistry.PLAY, PacketDirection.SERVERBOUND,
                        PluginMessagePacket.class),
                new Expectation("play", "clientbound", "minecraft:start_configuration",
                        StateRegistry.PLAY, PacketDirection.CLIENTBOUND,
                        StartConfigurationPacket.class),
                new Expectation("play", "clientbound", "minecraft:disconnect",
                        StateRegistry.PLAY, PacketDirection.CLIENTBOUND,
                        PlayDisconnectPacket.class),
                new Expectation("play", "clientbound", "minecraft:system_chat",
                        StateRegistry.PLAY, PacketDirection.CLIENTBOUND,
                        SystemChatPacket.class),
                new Expectation("play", "clientbound", "minecraft:custom_payload",
                        StateRegistry.PLAY, PacketDirection.CLIENTBOUND,
                        PluginMessagePacket.class));
    }

    /**
     * One test per packet per version, so a failure names exactly what moved.
     *
     * <p>A single test asserting everything would report the first mismatch and hide the
     * rest, and these arrive in groups: one inserted packet shifts everything after it.
     * Seeing all six at once is what tells you a whole block slid by one rather than that
     * somebody mistyped a hex digit.
     */
    @TestFactory
    Stream<DynamicTest> relayAgreesWithMojangOnEveryVerifiedVersion() {
        List<DynamicTest> tests = new ArrayList<>();
        for (int protocol : VERIFIED) {
            JsonObject report = load(protocol);
            ProtocolVersion version = ProtocolVersion.byId(protocol);
            String release = report.get("_release").getAsString();
            assertNotNull(version, "there is a report for protocol " + protocol
                    + " but Relay does not claim to support it");

            for (Expectation expectation : expectations()) {
                JsonObject direction = report.getAsJsonObject(expectation.state())
                        .getAsJsonObject(expectation.direction());
                if (!direction.has(expectation.mojangName())) {
                    continue;
                }
                int mojang = direction.getAsJsonObject(expectation.mojangName())
                        .get("protocol_id").getAsInt();
                String name = release + " " + expectation.state() + "/"
                        + expectation.direction() + "/" + expectation.mojangName();
                tests.add(DynamicTest.dynamicTest(name, () -> {
                    int relay = expectation.registry()
                            .forDirection(expectation.packetDirection())
                            .idOf(expectation.type(), version);
                    assertEquals(mojang, relay, () -> String.format(
                            "%s: Mojang's report says 0x%02X, Relay's table says 0x%02X. "
                                    + "The report is generated by the server itself and is "
                                    + "right; fix StateRegistry and docs/protocol-ids.md.",
                            name, mojang, relay));
                }));
            }
        }
        return tests.stream();
    }

    private static JsonObject load(int protocol) {
        String path = "/protocol/packets-" + protocol + ".json";
        try (InputStream in = MojangPacketReportTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing test resource " + path);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (Exception e) {
            throw new AssertionError("could not read " + path, e);
        }
    }
}
