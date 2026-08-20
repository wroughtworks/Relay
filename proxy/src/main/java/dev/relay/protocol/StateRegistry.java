package dev.relay.protocol;

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

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Maps packet ids to packet classes, per state, per direction, per protocol version.
 *
 * <p>Relay registers roughly twenty packets out of the several hundred Minecraft
 * defines. Everything else is relayed as an opaque frame. That is a deliberate
 * containment strategy for the risk spec &sect;11 opens with: adding a Minecraft version
 * means checking this file, not auditing a protocol dump.
 *
 * <h2>Failure modes, by design</h2>
 * The registrations are arranged so a wrong id degrades rather than corrupts:
 * <ul>
 *   <li><b>Clientbound play is write-only.</b> Relay never decodes backend&rarr;client
 *       play traffic, so gameplay cannot break on a bad id. A wrong clientbound id
 *       affects only packets Relay itself generates &mdash; switch prompts and proxy
 *       chat.</li>
 *   <li><b>Serverbound play decodes exactly one packet</b>, the unsigned chat command.
 *       If its id is wrong, commands fall through to the backend and are reported as
 *       unknown &mdash; annoying, obvious, and harmless.</li>
 *   <li><b>Handshake, status, login and configuration ids are stable</b> across the
 *       supported range apart from the documented 1.20.5 cookie insertions.</li>
 * </ul>
 *
 * <p>Play-state ids still shift whenever Mojang inserts a packet mid-list, so they can
 * be corrected from {@code relay.toml} without a rebuild &mdash; see
 * {@link #override}. Verify them against a protocol reference when adding a version.
 */
public final class StateRegistry {

    public static final StateRegistry HANDSHAKE = new StateRegistry(ProtocolState.HANDSHAKE);
    public static final StateRegistry STATUS = new StateRegistry(ProtocolState.STATUS);
    public static final StateRegistry LOGIN = new StateRegistry(ProtocolState.LOGIN);
    public static final StateRegistry CONFIGURATION = new StateRegistry(ProtocolState.CONFIGURATION);
    public static final StateRegistry PLAY = new StateRegistry(ProtocolState.PLAY);

    private static final ProtocolVersion V1_20_2 = ProtocolVersion.MINECRAFT_1_20_2;
    private static final ProtocolVersion V1_20_3 = ProtocolVersion.MINECRAFT_1_20_3;
    private static final ProtocolVersion V1_20_5 = ProtocolVersion.MINECRAFT_1_20_5;
    private static final ProtocolVersion V1_21 = ProtocolVersion.MINECRAFT_1_21;
    private static final ProtocolVersion V1_21_2 = ProtocolVersion.MINECRAFT_1_21_2;
    private static final ProtocolVersion V1_21_4 = ProtocolVersion.MINECRAFT_1_21_4;
    private static final ProtocolVersion V1_21_5 = ProtocolVersion.MINECRAFT_1_21_5;
    private static final ProtocolVersion V1_21_6 = ProtocolVersion.MINECRAFT_1_21_6;

    static {
        // ------------------------------------------------------------ handshake
        HANDSHAKE.serverbound.register("handshake", HandshakePacket.class, HandshakePacket::new,
                map(0x00, V1_20_2));

        // ------------------------------------------------------------ status
        STATUS.serverbound.register("status_request", StatusRequestPacket.class, () -> StatusRequestPacket.INSTANCE,
                map(0x00, V1_20_2));
        STATUS.serverbound.register("status_ping", StatusPingPacket.class, StatusPingPacket::new,
                map(0x01, V1_20_2));
        STATUS.clientbound.register("status_response", StatusResponsePacket.class, StatusResponsePacket::new,
                map(0x00, V1_20_2));
        STATUS.clientbound.register("status_pong", StatusPingPacket.class, StatusPingPacket::new,
                map(0x01, V1_20_2));

        // ------------------------------------------------------------ login
        // Stable across the supported range; 1.20.5 appended cookie packets after the
        // ids Relay uses rather than inserting before them.
        LOGIN.serverbound.register("login_start", LoginStartPacket.class, LoginStartPacket::new,
                map(0x00, V1_20_2));
        LOGIN.serverbound.register("encryption_response", EncryptionResponsePacket.class,
                EncryptionResponsePacket::new, map(0x01, V1_20_2));
        LOGIN.serverbound.register("login_plugin_response", LoginPluginResponsePacket.class,
                LoginPluginResponsePacket::new, map(0x02, V1_20_2));
        LOGIN.serverbound.register("login_acknowledged", LoginAcknowledgedPacket.class,
                () -> LoginAcknowledgedPacket.INSTANCE, map(0x03, V1_20_2));

        LOGIN.clientbound.register("login_disconnect", LoginDisconnectPacket.class, LoginDisconnectPacket::new,
                map(0x00, V1_20_2));
        LOGIN.clientbound.register("encryption_request", EncryptionRequestPacket.class,
                EncryptionRequestPacket::new, map(0x01, V1_20_2));
        LOGIN.clientbound.register("login_success", LoginSuccessPacket.class, LoginSuccessPacket::new,
                map(0x02, V1_20_2));
        LOGIN.clientbound.register("set_compression", SetCompressionPacket.class, SetCompressionPacket::new,
                map(0x03, V1_20_2));
        LOGIN.clientbound.register("login_plugin_request", LoginPluginRequestPacket.class,
                LoginPluginRequestPacket::new, map(0x04, V1_20_2));

        // ------------------------------------------------------------ configuration
        // 1.20.5 inserted Cookie Request / Cookie Response at id 0, shifting everything
        // after it by one in both directions.
        CONFIGURATION.serverbound.register("finish_configuration_ack", FinishConfigurationAckPacket.class,
                () -> FinishConfigurationAckPacket.INSTANCE,
                map(0x02, V1_20_2), map(0x03, V1_20_5));
        CONFIGURATION.serverbound.register("plugin_message", PluginMessagePacket.class, PluginMessagePacket::new,
                map(0x01, V1_20_2), map(0x02, V1_20_5));

        CONFIGURATION.clientbound.register("plugin_message", PluginMessagePacket.class, PluginMessagePacket::new,
                map(0x00, V1_20_2), map(0x01, V1_20_5));
        CONFIGURATION.clientbound.register("finish_configuration", FinishConfigurationPacket.class,
                () -> FinishConfigurationPacket.INSTANCE,
                map(0x02, V1_20_2), map(0x03, V1_20_5));
        // Write-only: a backend kick is forwarded as an opaque frame, never re-encoded.
        CONFIGURATION.clientbound.registerWriteOnly("disconnect", ConfigDisconnectPacket.class,
                map(0x01, V1_20_2), map(0x02, V1_20_5));

        // ------------------------------------------------------------ play
        //
        // Every id from 1.21 upward is read from Mojang's own packet report, generated by
        // the server jar itself (`--reports`). MojangPacketReportTest holds this table
        // against those reports on every version that has one. Before that check existed
        // these were inferred from the protocol layout and 26 of 72 were wrong -- badly
        // enough that switching servers could not have worked at all on 1.21.6+.
        //
        // 1.20.3-1.20.6 (765, 766) are the exception and remain inferred: Mojang's report
        // provider did not exist yet. They are bracketed by verified neighbours rather
        // than extrapolated, which is better than they were and still not proof.

        // The only serverbound play packet Relay opens. On 1.20.2/1.20.3 this is the
        // single (signed) command packet; from 1.20.5 it is the unsigned variant, with
        // the signed form left to pass through untouched.
        PLAY.serverbound.register("chat_command", ChatCommandPacket.class, ChatCommandPacket::new,
                map(0x04, V1_20_2), map(0x05, V1_21_2), map(0x06, V1_21_6));
        PLAY.serverbound.register("configuration_acknowledged", ConfigurationAcknowledgedPacket.class,
                () -> ConfigurationAcknowledgedPacket.INSTANCE,
                map(0x0B, V1_20_2), map(0x0C, V1_20_5), map(0x0E, V1_21_2), map(0x0F, V1_21_6));

        // Plugin messages in play state, for the client API (docs/client-api.md).
        //
        // Serverbound must be decoded, so its id has to be right. It was wrong at every
        // version above 1.20.4: the reasoning was that it tracks Acknowledge
        // Configuration's shifts exactly, and it does not -- packets were inserted
        // between the two. Reasoning about neighbours is how this table went wrong.
        PLAY.serverbound.register("plugin_message", PluginMessagePacket.class, PluginMessagePacket::new,
                map(0x0F, V1_20_2), map(0x12, V1_20_5), map(0x14, V1_21_2), map(0x15, V1_21_6));
        // Clientbound is write-only on purpose. Relay only ever *sends* API messages to a
        // player; a backend's own plugin messages are relayed as opaque frames and never
        // parsed. Registering this decodable would mean a wrong id makes Relay misread
        // ordinary backend traffic and kill the connection -- all risk, no benefit.
        PLAY.clientbound.registerWriteOnly("plugin_message", PluginMessagePacket.class,
                map(0x18, V1_20_2), map(0x19, V1_20_5), map(0x18, V1_21_5));

        // All write-only. Relay generates these; it never parses them.
        PLAY.clientbound.registerWriteOnly("disconnect", PlayDisconnectPacket.class,
                map(0x1B, V1_20_2), map(0x1D, V1_20_5), map(0x1C, V1_21_5));
        // 0x67 at 1.20.2 is confirmed against a live Paper 1.20.2 debug log
        // ("OUT: [play:103] ClientboundSystemChatPacket"). Everything above 1.21 comes
        // from the packet reports -- and note that system_chat and start_configuration
        // cross over at 1.21.5: the old "+2 offset from start_configuration" rule held at
        // 1.20.2 and nowhere near the top of the range.
        PLAY.clientbound.registerWriteOnly("system_chat", SystemChatPacket.class,
                map(0x67, V1_20_2), map(0x69, V1_20_3), map(0x6C, V1_20_5), map(0x73, V1_21_2),
                map(0x72, V1_21_5));
        PLAY.clientbound.registerWriteOnly("start_configuration", StartConfigurationPacket.class,
                map(0x65, V1_20_2), map(0x67, V1_20_3), map(0x69, V1_20_5), map(0x70, V1_21_2),
                map(0x6F, V1_21_5));

        // Two packets sharing an id in one state and direction is always a mistake, and
        // one that stays silent until a player sees the wrong packet. The table above is
        // hand-maintained, so it is checked rather than trusted.
        verifyNoDuplicateIds();
    }

    /**
     * Fails fast if any two packets claim the same id at the same version.
     *
     * <p>This exists because exactly that bug shipped: {@code system_chat} and
     * {@code start_configuration} both claimed {@code 0x67} at 1.20.3 and {@code 0x69} at
     * 1.20.5. Both are write-only, so nothing threw &mdash; the client simply received a
     * chat message where a state change belonged.
     */
    private static void verifyNoDuplicateIds() {
        for (StateRegistry registry : new StateRegistry[]{HANDSHAKE, STATUS, LOGIN, CONFIGURATION, PLAY}) {
            for (PacketRegistry direction : new PacketRegistry[]{registry.clientbound, registry.serverbound}) {
                for (ProtocolVersion version : ProtocolVersion.values()) {
                    Map<Integer, Class<? extends Packet>> seen = new HashMap<>();
                    for (Map.Entry<Class<? extends Packet>, Integer> entry
                            : direction.versions.get(version).classToId.entrySet()) {
                        Class<? extends Packet> clash = seen.put(entry.getValue(), entry.getKey());
                        if (clash != null) {
                            throw new IllegalStateException("Packet id 0x" + Integer.toHexString(entry.getValue())
                                    + " is claimed by both " + clash.getSimpleName() + " and "
                                    + entry.getKey().getSimpleName() + " in " + registry.state + "/"
                                    + direction.direction() + " at " + version);
                        }
                    }
                }
            }
        }
    }

    private final ProtocolState state;
    public final PacketRegistry clientbound;
    public final PacketRegistry serverbound;

    private StateRegistry(ProtocolState state) {
        this.state = state;
        this.clientbound = new PacketRegistry(PacketDirection.CLIENTBOUND);
        this.serverbound = new PacketRegistry(PacketDirection.SERVERBOUND);
    }

    public ProtocolState state() {
        return state;
    }

    public PacketRegistry forDirection(PacketDirection direction) {
        return direction == PacketDirection.CLIENTBOUND ? clientbound : serverbound;
    }

    public static StateRegistry of(ProtocolState state) {
        return switch (state) {
            case HANDSHAKE -> HANDSHAKE;
            case STATUS -> STATUS;
            case LOGIN -> LOGIN;
            case CONFIGURATION -> CONFIGURATION;
            case PLAY -> PLAY;
        };
    }

    /**
     * Corrects a packet id at startup, before any connection exists.
     *
     * <p>Exists so a protocol change can be absorbed by editing {@code relay.toml}
     * instead of shipping a new build &mdash; the difference between a five-minute fix
     * and a release, on the day a Minecraft update lands.
     *
     * @throws IllegalArgumentException if no packet is registered under {@code name}
     */
    public static void override(ProtocolState state, PacketDirection direction, String name,
                                ProtocolVersion version, int id) {
        of(state).forDirection(direction).override(name, version, id);
    }

    private static Mapping map(int id, ProtocolVersion from) {
        return new Mapping(id, from);
    }

    /** A packet id that holds from {@code from} until the next mapping supersedes it. */
    private record Mapping(int id, ProtocolVersion from) {
    }

    /** The packets of one state travelling in one direction. */
    public static final class PacketRegistry {

        private final PacketDirection direction;
        private final Map<ProtocolVersion, VersionRegistry> versions = new EnumMap<>(ProtocolVersion.class);
        private final Map<String, Class<? extends Packet>> byName = new HashMap<>();

        private PacketRegistry(PacketDirection direction) {
            this.direction = direction;
            for (ProtocolVersion version : ProtocolVersion.values()) {
                versions.put(version, new VersionRegistry());
            }
        }

        public PacketDirection direction() {
            return direction;
        }

        private void register(String name, Class<? extends Packet> type, Supplier<? extends Packet> factory,
                              Mapping... mappings) {
            register(name, type, factory, true, mappings);
        }

        /** Registers a packet Relay only ever writes, so a wrong id cannot break decoding. */
        private void registerWriteOnly(String name, Class<? extends Packet> type, Mapping... mappings) {
            register(name, type, null, false, mappings);
        }

        private void register(String name, Class<? extends Packet> type, Supplier<? extends Packet> factory,
                              boolean decodable, Mapping... mappings) {
            if (mappings.length == 0) {
                throw new IllegalArgumentException("Packet " + name + " has no version mappings");
            }
            byName.put(name, type);
            for (int i = 0; i < mappings.length; i++) {
                Mapping mapping = mappings[i];
                ProtocolVersion last = i + 1 < mappings.length ? previous(mappings[i + 1].from()) : ProtocolVersion.newest();
                for (ProtocolVersion version : ProtocolVersion.values()) {
                    if (version.atLeast(mapping.from()) && version.atMost(last)) {
                        VersionRegistry registry = versions.get(version);
                        registry.classToId.put(type, mapping.id());
                        if (decodable) {
                            registry.idToFactory.put(mapping.id(), factory);
                        }
                    }
                }
            }
        }

        private void override(String name, ProtocolVersion version, int id) {
            Class<? extends Packet> type = byName.get(name);
            if (type == null) {
                throw new IllegalArgumentException("No packet named '" + name + "' in " + direction + " registry; known: "
                        + byName.keySet());
            }
            VersionRegistry registry = versions.get(version);
            Integer oldId = registry.classToId.put(type, id);
            if (oldId != null) {
                Supplier<? extends Packet> factory = registry.idToFactory.remove(oldId);
                if (factory != null) {
                    registry.idToFactory.put(id, factory);
                }
            }
        }

        /** @return a fresh packet instance, or {@code null} if this id is not registered */
        public Packet create(int id, ProtocolVersion version) {
            Supplier<? extends Packet> factory = versions.get(version).idToFactory.get(id);
            return factory == null ? null : factory.get();
        }

        /** @return the wire id for {@code type}, or {@code -1} if it has no id at this version */
        public int idOf(Class<? extends Packet> type, ProtocolVersion version) {
            Integer id = versions.get(version).classToId.get(type);
            return id == null ? -1 : id;
        }

        private static ProtocolVersion previous(ProtocolVersion version) {
            ProtocolVersion[] all = ProtocolVersion.values();
            int index = version.ordinal();
            return index == 0 ? all[0] : all[index - 1];
        }
    }

    private static final class VersionRegistry {
        private final Map<Integer, Supplier<? extends Packet>> idToFactory = new HashMap<>();
        private final Map<Class<? extends Packet>, Integer> classToId = new HashMap<>();
    }
}
