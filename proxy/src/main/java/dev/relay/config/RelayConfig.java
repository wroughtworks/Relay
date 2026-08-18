package dev.relay.config;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parsed contents of {@code relay.toml}.
 *
 * <p>Immutable by construction. Spec &sect;6.3 keeps config in the file rather than the
 * database so it stays git-able and single-sourced; reloading therefore means building a
 * new instance, not mutating this one.
 */
public record RelayConfig(
        Path sourcePath,
        InetSocketAddress bind,
        String motd,
        int maxPlayers,
        boolean showOnlineCount,
        boolean onlineMode,
        ForwardingMode forwardingMode,
        byte[] forwardingSecret,
        String brand,
        int compressionThreshold,
        int compressionLevel,
        int connectTimeoutMillis,
        int readTimeoutMillis,
        boolean interceptCommands,
        boolean proxyProtocolReceive,
        boolean proxyProtocolSend,
        boolean clientApiEnabled,
        boolean backendApiEnabled,
        boolean traceCloses,
        Map<String, ServerEntry> servers,
        List<String> tryOrder,
        Map<String, List<String>> forcedHosts,
        Map<String, List<String>> permissions,
        List<ProtocolOverride> protocolOverrides) {

    public RelayConfig {
        // Insertion order is meaningful, not incidental: it is the order backends appear
        // in /server and in the startup log, and it decides the implicit fallback when no
        // "try" list is given. Map.copyOf would scramble it.
        servers = Collections.unmodifiableMap(new LinkedHashMap<>(servers));
        forcedHosts = Collections.unmodifiableMap(new LinkedHashMap<>(forcedHosts));
        permissions = Collections.unmodifiableMap(new LinkedHashMap<>(permissions));
        tryOrder = List.copyOf(tryOrder);
        protocolOverrides = List.copyOf(protocolOverrides);
    }

    /** A backend Relay can send players to. */
    public record ServerEntry(String name, InetSocketAddress address) {
    }

    /**
     * A packet-id correction from {@code [protocol.overrides]}.
     *
     * @param key the raw {@code state.direction.packet.protocolVersion} key, kept for
     *            error messages
     */
    public record ProtocolOverride(String key, String state, String direction, String packet,
                                   int protocolVersion, int packetId) {
    }

    /** The first backend a joining player should be sent to, honouring forced hosts. */
    public List<String> initialCandidates(String virtualHost) {
        if (virtualHost != null) {
            List<String> forced = forcedHosts.get(virtualHost.toLowerCase(java.util.Locale.ROOT));
            if (forced != null && !forced.isEmpty()) {
                return forced;
            }
        }
        return tryOrder;
    }
}
