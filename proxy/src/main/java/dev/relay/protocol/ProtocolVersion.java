package dev.relay.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Minecraft: Java Edition protocol versions Relay speaks.
 *
 * <p>Per the design spec (&sect;5.1) this is a deliberately narrow range rather than
 * "every version ever." The floor is <b>1.20.2</b>, which is where the configuration
 * phase was introduced &mdash; every version at or above it shares one connection
 * model, so the proxy needs exactly one server-switch implementation instead of two.
 * Supporting anything older would mean carrying the pre-configuration "fake respawn"
 * dance forever, which is the scope trap the spec warns about.
 */
public enum ProtocolVersion {
    MINECRAFT_1_20_2(764, "1.20.2"),
    MINECRAFT_1_20_3(765, "1.20.3", "1.20.4"),
    MINECRAFT_1_20_5(766, "1.20.5", "1.20.6"),
    MINECRAFT_1_21(767, "1.21", "1.21.1"),
    MINECRAFT_1_21_2(768, "1.21.2", "1.21.3"),
    MINECRAFT_1_21_4(769, "1.21.4"),
    MINECRAFT_1_21_5(770, "1.21.5"),
    MINECRAFT_1_21_6(771, "1.21.6"),
    MINECRAFT_1_21_7(772, "1.21.7", "1.21.8");

    /** Sentinel used before the handshake tells us what the client actually is. */
    public static final ProtocolVersion UNKNOWN = null;

    private static final Map<Integer, ProtocolVersion> BY_ID;

    static {
        Map<Integer, ProtocolVersion> byId = new LinkedHashMap<>();
        for (ProtocolVersion version : values()) {
            byId.put(version.id, version);
        }
        BY_ID = Collections.unmodifiableMap(byId);
    }

    private final int id;
    private final List<String> names;

    ProtocolVersion(int id, String... names) {
        this.id = id;
        this.names = List.of(names);
    }

    public int id() {
        return id;
    }

    /** Human-readable release names sharing this protocol number, e.g. {@code [1.21.7, 1.21.8]}. */
    public List<String> names() {
        return names;
    }

    public String displayName() {
        return names.size() == 1 ? names.get(0) : names.get(0) + "-" + names.get(names.size() - 1);
    }

    public boolean atLeast(ProtocolVersion other) {
        return id >= other.id;
    }

    public boolean atMost(ProtocolVersion other) {
        return id <= other.id;
    }

    public static ProtocolVersion byId(int id) {
        return BY_ID.get(id);
    }

    public static boolean isSupported(int id) {
        return BY_ID.containsKey(id);
    }

    public static ProtocolVersion oldest() {
        return values()[0];
    }

    public static ProtocolVersion newest() {
        return values()[values().length - 1];
    }

    /** e.g. {@code "1.20.2-1.21.8"} &mdash; used in the MOTD version string. */
    public static String supportedRange() {
        return oldest().names().get(0) + "-" + newest().names().get(newest().names().size() - 1);
    }

    @Override
    public String toString() {
        return displayName() + " (" + id + ")";
    }
}
