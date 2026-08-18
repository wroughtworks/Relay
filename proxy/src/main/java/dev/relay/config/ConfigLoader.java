package dev.relay.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlFormat;
import dev.relay.config.RelayConfig.ProtocolOverride;
import dev.relay.config.RelayConfig.ServerEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads {@code relay.toml}, writing a commented starter file on first run.
 *
 * <p>Every failure here is a startup failure with a message naming the offending key.
 * A proxy that boots with a half-understood config is worse than one that refuses to
 * boot: the former fails at 3am under load, the latter fails while someone is watching.
 */
public final class ConfigLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigLoader.class);

    private static final String DEFAULT_RESOURCE = "/relay.default.toml";
    private static final String SECRET_PLACEHOLDER = "CHANGE-ME";

    private ConfigLoader() {
    }

    public static RelayConfig load(Path path) throws IOException {
        if (Files.notExists(path)) {
            writeDefault(path);
            LOG.info("Wrote a starter configuration to {}", path.toAbsolutePath());
        }

        // Read and parse the text directly rather than through FileConfig, so the byte
        // order mark a Windows editor may prepend can be stripped first. Left in place it
        // becomes part of the first key, and TOML rejects it as "Invalid bare key" --
        // pointing at a line that looks perfectly correct on screen.
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
            LOG.warn("{} begins with a byte order mark, which has been ignored. Save it as UTF-8 without a BOM "
                    + "to avoid surprises.", path.getFileName());
        }

        try (Reader reader = new StringReader(text)) {
            Config config = TomlFormat.instance().createParser().parse(reader);
            return parse(config, path);
        }
    }

    private static void writeDefault(Path path) throws IOException {
        try (InputStream in = ConfigLoader.class.getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IOException("Packaged default config " + DEFAULT_RESOURCE + " is missing from the jar");
            }
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String contents = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace(SECRET_PLACEHOLDER, generateSecret());
            Files.writeString(path, contents, StandardCharsets.UTF_8);
        }
    }

    /** A fresh forwarding secret, so a new install is never deployed with a shared one. */
    private static String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().withoutPadding().encodeToString(bytes);
    }

    private static RelayConfig parse(Config config, Path path) {
        InetSocketAddress bind = parseAddress(require(config, "bind"), "bind");
        String motd = config.getOrElse("motd", "A Relay proxy");
        int maxPlayers = config.getIntOrElse("max-players", 100);
        boolean showOnlineCount = config.getOrElse("show-online-count", Boolean.TRUE);
        boolean onlineMode = config.getOrElse("online-mode", Boolean.TRUE);
        String brand = config.getOrElse("brand", "Relay");

        ForwardingMode forwardingMode = ForwardingMode.parse(config.getOrElse("forwarding-mode", "modern"));
        byte[] forwardingSecret = readForwardingSecret(config, forwardingMode, path);

        int compressionThreshold = config.getIntOrElse("compression-threshold", 256);
        int compressionLevel = config.getIntOrElse("compression-level", -1);
        if (compressionLevel < -1 || compressionLevel > 9) {
            throw new IllegalArgumentException("compression-level must be between -1 and 9, got " + compressionLevel);
        }
        int connectTimeout = config.getIntOrElse("connect-timeout", 5000);
        int readTimeout = config.getIntOrElse("read-timeout", 30000);
        boolean interceptCommands = config.getOrElse("intercept-commands", Boolean.TRUE);
        boolean proxyProtocolReceive = config.getOrElse("proxy-protocol-receive", Boolean.FALSE);
        boolean proxyProtocolSend = config.getOrElse("proxy-protocol-send", Boolean.FALSE);
        boolean clientApiEnabled = config.getOrElse("client-api", Boolean.TRUE);
        boolean backendApiEnabled = config.getOrElse("backend-api", Boolean.TRUE);
        // On by default: it costs one handler per connection and logs only when a
        // connection ends, and the alternative is a close that leaves no trace of who
        // caused it -- which is exactly the situation where it is needed and too late
        // to switch on.
        boolean traceCloses = config.getOrElse("trace-closes", Boolean.TRUE);

        Map<String, ServerEntry> servers = parseServers(config);
        if (servers.isEmpty()) {
            throw new IllegalArgumentException("No backends defined; add at least one entry under [servers]");
        }

        List<String> tryOrder = config.getOrElse("try", List.<String>of());
        if (tryOrder.isEmpty()) {
            // Fall back to the first declared backend so a minimal config still works.
            tryOrder = List.of(servers.keySet().iterator().next());
        }
        for (String name : tryOrder) {
            if (!servers.containsKey(name)) {
                throw new IllegalArgumentException("try lists unknown backend '" + name + "'");
            }
        }

        Map<String, List<String>> forcedHosts = parseForcedHosts(config, servers);
        Map<String, List<String>> permissions = parsePermissions(config);
        List<ProtocolOverride> overrides = parseProtocolOverrides(config);

        return new RelayConfig(path.toAbsolutePath(),
                bind, motd, maxPlayers, showOnlineCount, onlineMode, forwardingMode,
                forwardingSecret, brand, compressionThreshold, compressionLevel, connectTimeout, readTimeout,
                interceptCommands, proxyProtocolReceive, proxyProtocolSend, clientApiEnabled, backendApiEnabled, traceCloses,
                servers, tryOrder, forcedHosts, permissions, overrides);
    }

    /**
     * Reads {@code [permissions]}: a {@code default} list plus per-player grants keyed by
     * username or UUID.
     */
    private static Map<String, List<String>> parsePermissions(Config config) {
        Config section = config.get("permissions");
        Map<String, List<String>> permissions = new LinkedHashMap<>();
        if (section == null) {
            return permissions;
        }
        for (Config.Entry entry : section.entrySet()) {
            Object value = entry.getValue();
            List<String> nodes = value instanceof String single ? List.of(single) : entry.getValue();
            permissions.put(entry.getKey().toLowerCase(Locale.ROOT), List.copyOf(nodes));
        }
        return permissions;
    }

    private static Map<String, ServerEntry> parseServers(Config config) {
        Config section = config.get("servers");
        Map<String, ServerEntry> servers = new LinkedHashMap<>();
        if (section == null) {
            return servers;
        }
        for (Config.Entry entry : section.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (!(value instanceof String address)) {
                throw new IllegalArgumentException(
                        "Backend '" + name + "' must be a \"host:port\" string, got " + value);
            }
            servers.put(name, new ServerEntry(name, parseAddress(address, "servers." + name)));
        }
        return servers;
    }

    private static Map<String, List<String>> parseForcedHosts(Config config, Map<String, ServerEntry> servers) {
        Config section = config.get("forced-hosts");
        Map<String, List<String>> hosts = new LinkedHashMap<>();
        if (section == null) {
            return hosts;
        }
        for (Config.Entry entry : section.entrySet()) {
            Object value = entry.getValue();
            List<String> targets = value instanceof String single ? List.of(single) : entry.getValue();
            for (String target : targets) {
                if (!servers.containsKey(target)) {
                    throw new IllegalArgumentException(
                            "forced-hosts entry '" + entry.getKey() + "' names unknown backend '" + target + "'");
                }
            }
            hosts.put(entry.getKey().toLowerCase(Locale.ROOT), List.copyOf(targets));
        }
        return hosts;
    }

    /**
     * Parses {@code [protocol.overrides]} keys of the form
     * {@code state.direction.packet.protocolVersion}.
     */
    private static List<ProtocolOverride> parseProtocolOverrides(Config config) {
        Config section = config.get("protocol.overrides");
        List<ProtocolOverride> overrides = new ArrayList<>();
        if (section == null) {
            return overrides;
        }
        for (Config.Entry entry : section.entrySet()) {
            String key = entry.getKey();
            String[] parts = key.split("\\.");
            if (parts.length != 4) {
                throw new IllegalArgumentException("protocol override key '" + key
                        + "' must look like state.direction.packet.protocolVersion");
            }
            int protocolVersion;
            try {
                protocolVersion = Integer.parseInt(parts[3]);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        "protocol override key '" + key + "' has a non-numeric protocol version '" + parts[3] + "'");
            }
            Object value = entry.getValue();
            if (!(value instanceof Number id)) {
                throw new IllegalArgumentException("protocol override '" + key + "' must be an integer packet id");
            }
            overrides.add(new ProtocolOverride(key, parts[0], parts[1], parts[2], protocolVersion, id.intValue()));
        }
        return overrides;
    }

    private static byte[] readForwardingSecret(Config config, ForwardingMode mode, Path configPath) {
        if (mode != ForwardingMode.MODERN) {
            return new byte[0];
        }

        String inlineSecret = config.get("forwarding-secret");
        String secretFile = config.get("forwarding-secret-file");

        String secret;
        if (secretFile != null) {
            Path resolved = configPath.toAbsolutePath().getParent().resolve(secretFile);
            try {
                secret = Files.readString(resolved, StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                throw new IllegalArgumentException("Cannot read forwarding-secret-file " + resolved, e);
            }
        } else {
            secret = inlineSecret;
        }

        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException(
                    "forwarding-mode is 'modern' but no forwarding-secret is set. "
                            + "Set the same value here and in each backend's config.");
        }
        if (secret.equals(SECRET_PLACEHOLDER)) {
            throw new IllegalArgumentException(
                    "forwarding-secret is still the placeholder value. Generate a real one before exposing the proxy.");
        }

        // The secret is HMAC key material, so a stray space or newline changes it
        // completely while looking identical in an editor. Trimming is safe -- no real
        // secret depends on surrounding whitespace -- and silently not trimming would
        // produce a signature mismatch with no visible cause.
        String cleaned = secret.strip();
        if (!cleaned.equals(secret)) {
            LOG.warn("forwarding-secret had surrounding whitespace, which has been ignored. "
                    + "Make sure the backend's copy does not include it either.");
        }
        return cleaned.getBytes(StandardCharsets.UTF_8);
    }

    private static String require(Config config, String key) {
        String value = config.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Required config key '" + key + "' is missing");
        }
        return value;
    }

    /** Parses {@code host:port}, accepting bracketed IPv6 literals. */
    static InetSocketAddress parseAddress(String value, String key) {
        String trimmed = value.trim();
        int separator;
        if (trimmed.startsWith("[")) {
            int close = trimmed.indexOf(']');
            if (close == -1) {
                throw new IllegalArgumentException(key + ": unterminated IPv6 literal in '" + value + "'");
            }
            separator = trimmed.indexOf(':', close);
        } else {
            separator = trimmed.lastIndexOf(':');
        }
        if (separator == -1) {
            throw new IllegalArgumentException(key + ": expected \"host:port\", got '" + value + "'");
        }

        String host = trimmed.substring(0, separator);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        int port;
        try {
            port = Integer.parseInt(trimmed.substring(separator + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + ": port is not a number in '" + value + "'");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(key + ": port " + port + " is out of range 1-65535");
        }
        return InetSocketAddress.createUnresolved(host, port);
    }
}
