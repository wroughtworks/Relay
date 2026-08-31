package dev.relay.config;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import dev.relay.config.RelayConfig.CompanionEntry;
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
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code relay.toml}, writing a commented starter file on first run.
 *
 * <p>Every failure here is a startup failure with a message naming the offending key.
 * A proxy that boots with a half-understood config is worse than one that refuses to
 * boot: the former fails at 3am under load, the latter fails while someone is watching.
 */
public final class ConfigLoader {

    /**
     * A backend that looks like one of several numbered copies.
     *
     * <p>The separator is required: without it {@code s3} would name a group {@code s},
     * and a rule that fires on names nobody meant as a group is worse than no rule.
     */
    private static final Pattern NUMBERED_SERVER = Pattern.compile("^(.*[^-_])[-_]\\d+$");

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
            // Parsed into a config backed by a LinkedHashMap rather than letting the
            // parser build its own. Nightconfig's default is a HashMap, so declaration
            // order is lost -- and this file depends on it in three places: the /server
            // listing, the startup log, and the implicit fallback when no "try" list is
            // given, which otherwise sends players to an arbitrary backend.
            Config config = Config.of(LinkedHashMap::new, TomlFormat.instance());
            TomlFormat.instance().createParser().parse(reader, config, ParsingMode.REPLACE);
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

    /**
     * The machine's hostname, or a generic label if it will not say.
     *
     * <p>A better default than "relay": on the day there are two nodes, routes read
     * against distinct names without anyone having configured anything.
     */
    private static String defaultNodeName() {
        try {
            String host = java.net.InetAddress.getLocalHost().getHostName();
            return host == null || host.isBlank() ? "relay" : host;
        } catch (java.net.UnknownHostException e) {
            return "relay";
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
        // Spec 7.9: a stable identity for this node. One proxy today, so it is only a
        // label -- but it is the label a player's route is read against, and hard-coding
        // "relay" now would mean every route on every node looking identical later.
        String nodeName = config.getOrElse("node-name", ConfigLoader::defaultNodeName);
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
        // On by default: without it, restarting one backend returns every player on
        // it to the multiplayer menu instead of the lobby.
        boolean fallbackOnBackendLoss = config.getOrElse("fallback-on-backend-loss", Boolean.TRUE);
        boolean proxyProtocolReceive = config.getOrElse("proxy-protocol-receive", Boolean.FALSE);
        boolean proxyProtocolSend = config.getOrElse("proxy-protocol-send", Boolean.FALSE);
        boolean clientApiEnabled = config.getOrElse("client-api", Boolean.TRUE);
        boolean backendApiEnabled = config.getOrElse("backend-api", Boolean.TRUE);
        // On by default: it costs one handler per connection and logs only when a
        // connection ends, and the alternative is a close that leaves no trace of who
        // caused it -- which is exactly the situation where it is needed and too late
        // to switch on.
        boolean traceCloses = config.getOrElse("trace-closes", Boolean.TRUE);

        // On by default, unlike the dashboard: this one only makes routing better, and
        // costs one status ping per backend per interval -- the same request a server
        // list refresh makes.
        // On by default. It is one small file beside the config, it is what makes the
        // dashboard able to answer "what happened last night", and a proxy that records
        // nothing cannot be asked afterwards.
        // On by default, and the default is measured rather than assumed. Batching
        // flushes trades a little latency for far fewer syscalls, and the crossover sits
        // between 8 and 16 concurrent connections: below it, flushing per packet wins by
        // about 30%; above it, batching wins by 10-15% and keeps winning. Any real
        // network is above it. A knob, because a two-player test server is not.
        boolean flushBatching = config.getOrElse("flush-batching", Boolean.TRUE);

        boolean storageEnabled = config.getOrElse("storage.enabled", Boolean.TRUE);
        String storageFile = config.getOrElse("storage.file", "relay.db");
        int storageRetainDays = config.getIntOrElse("storage.retain-days", 14);

        boolean healthEnabled = config.getOrElse("health.enabled", Boolean.TRUE);
        int healthInterval = config.getIntOrElse("health.interval", 10000);
        int healthTimeout = config.getIntOrElse("health.timeout", 3000);
        int healthFailures = config.getIntOrElse("health.failures-before-down", 3);
        if (healthInterval < 1000) {
            throw new IllegalArgumentException("health.interval must be at least 1000ms, got " + healthInterval);
        }
        if (healthFailures < 1) {
            throw new IllegalArgumentException("health.failures-before-down must be at least 1, got "
                    + healthFailures);
        }

        // On by default, but it publishes nothing on its own: it is loopback, token
        // gated, and silent until a companion connects. Off, no companion can start.
        boolean controlEnabled = config.getOrElse("control.enabled", Boolean.TRUE);
        InetSocketAddress controlBind = parseAddress(
                config.getOrElse("control.bind", "127.0.0.1:25580"), "control.bind");
        List<CompanionEntry> companions = parseCompanions(config);

        Map<String, ServerEntry> servers = parseServers(config);
        if (servers.isEmpty()) {
            throw new IllegalArgumentException("No backends defined; add at least one entry under [servers]");
        }

        Map<String, List<String>> groups = parseGroups(config, servers);
        BalanceStrategy balance = BalanceStrategy.parse(config.getOrElse("balance", "least-players"));

        List<String> tryOrder = config.getOrElse("try", List.<String>of());
        if (tryOrder.isEmpty()) {
            // Fall back to the first declared backend so a minimal config still works.
            tryOrder = List.of(servers.keySet().iterator().next());
        }
        for (String name : tryOrder) {
            requireDestination(servers, groups, name, "try");
        }

        Map<String, List<String>> forcedHosts = parseForcedHosts(config, servers, groups);
        Map<String, List<String>> permissions = parsePermissions(config);
        List<ProtocolOverride> overrides = parseProtocolOverrides(config);

        return new RelayConfig(path.toAbsolutePath(),
                bind, nodeName, motd, maxPlayers, showOnlineCount, onlineMode, forwardingMode,
                forwardingSecret, brand, compressionThreshold, compressionLevel, connectTimeout, readTimeout,
                interceptCommands, fallbackOnBackendLoss, proxyProtocolReceive, proxyProtocolSend, clientApiEnabled, backendApiEnabled, traceCloses,
                healthEnabled, healthInterval, healthTimeout, healthFailures,
                flushBatching, storageEnabled, storageFile, storageRetainDays,
                controlEnabled, controlBind, companions,
                servers, groups, balance, tryOrder, forcedHosts, permissions, overrides);
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

    /**
     * Reads {@code [groups]}: a name, and the interchangeable backends behind it.
     *
     * <p>Validated strictly, because every mistake here is otherwise silent at startup
     * and confusing later. A group that shadows a backend name would make {@code /server}
     * ambiguous; a group naming a backend that does not exist would send players nowhere
     * the first time that member came up in the rotation.
     */
    private static Map<String, List<String>> parseGroups(Config config, Map<String, ServerEntry> servers) {
        Config section = config.get("groups");
        Map<String, List<String>> groups = new LinkedHashMap<>();
        if (section == null) {
            return deriveGroups(servers, groups);
        }
        for (Config.Entry entry : section.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            if (servers.containsKey(name)) {
                throw new IllegalArgumentException("Group '" + name + "' has the same name as a backend; "
                        + "one name cannot mean both");
            }
            Object value = entry.getValue();
            List<String> members = value instanceof String single ? List.of(single) : entry.getValue();
            if (members.isEmpty()) {
                throw new IllegalArgumentException("Group '" + name + "' lists no backends");
            }
            for (String member : members) {
                if (!servers.containsKey(member)) {
                    throw new IllegalArgumentException(
                            "Group '" + name + "' names unknown backend '" + member + "'");
                }
            }
            groups.put(name, List.copyOf(members));
        }
        return deriveGroups(servers, groups);
    }

    /**
     * Groups backends whose names already say they belong together.
     *
     * <p>{@code survival-01} and {@code survival-02} are a group called {@code survival}
     * without anyone writing that down. Numbering servers is what people do anyway, so
     * the config for the common case becomes no config at all &mdash; and the alternative,
     * repeating every backend name a second time under {@code [groups]}, is a list that
     * can silently fall out of date the next time a server is added.
     *
     * <p>Deliberately narrow. Only a separator followed by digits counts, so
     * {@code pvp-arena} and {@code lobby} are left alone; a name has to look like one of
     * several numbered copies, not merely contain a dash.
     *
     * <p>Anything written under {@code [groups]} wins, and a real backend's name always
     * wins over a derived group, so nothing here can override something stated outright.
     */
    private static Map<String, List<String>> deriveGroups(Map<String, ServerEntry> servers,
                                                          Map<String, List<String>> explicit) {
        Map<String, List<String>> derived = new LinkedHashMap<>();
        for (String server : servers.keySet()) {
            Matcher matcher = NUMBERED_SERVER.matcher(server);
            if (!matcher.matches()) {
                continue;
            }
            String base = matcher.group(1);
            // A backend of that name already means something, and a group cannot shadow
            // it. Someone running "survival" beside "survival-01" gets the backend.
            if (servers.containsKey(base) || explicit.containsKey(base)) {
                continue;
            }
            derived.computeIfAbsent(base, key -> new ArrayList<>()).add(server);
        }

        Map<String, List<String>> all = new LinkedHashMap<>(explicit);
        derived.forEach((name, members) -> all.put(name, List.copyOf(members)));
        return all;
    }

    /**
     * Reads {@code [companions]}: the processes Relay starts beside itself.
     *
     * <p>The command is a list rather than a string. Splitting a command line correctly is
     * a job nobody gets right on the first attempt -- quoting, escapes, and on Windows
     * paths with spaces in them as the normal case -- and getting it wrong produces a
     * process that fails to start for reasons the operator cannot see in their own config.
     */
    private static List<CompanionEntry> parseCompanions(Config config) {
        Config section = config.get("companions");
        List<CompanionEntry> companions = new ArrayList<>();
        if (section == null) {
            return companions;
        }
        for (Config.Entry entry : section.entrySet()) {
            String name = entry.getKey();
            Object value = entry.getValue();
            if (!(value instanceof Config companion)) {
                throw new IllegalArgumentException("Companion '" + name
                        + "' must be a table, for example [companions." + name + "]");
            }
            Object command = companion.get("command");
            List<String> arguments;
            if (command instanceof List<?> list) {
                // Objects.toString, never String::valueOf. Against a wildcard element
                // type the compiler resolves that method reference to valueOf(char[]),
                // and every string argument then fails to cast at runtime -- which
                // presents as a config error naming no key at all.
                arguments = list.stream().map(Objects::toString).toList();
            } else if (command instanceof String single && !single.isBlank()) {
                arguments = List.of(single);
            } else {
                throw new IllegalArgumentException("Companion '" + name
                        + "' needs a command, as a list of arguments");
            }

            Map<String, String> environment = new LinkedHashMap<>();
            Object env = companion.get("environment");
            if (env instanceof Config table) {
                for (Config.Entry variable : table.entrySet()) {
                    environment.put(variable.getKey(), Objects.toString(variable.getValue()));
                }
            }

            companions.add(new CompanionEntry(name, arguments,
                    companion.getOrElse("enabled", Boolean.TRUE),
                    companion.getOrElse("restart", Boolean.TRUE),
                    environment));
        }
        return companions;
    }

    /** Accepts either a backend or a group, since anywhere a player can be sent takes both. */
    private static void requireDestination(Map<String, ServerEntry> servers, Map<String, List<String>> groups,
                                           String name, String where) {
        if (!servers.containsKey(name) && !groups.containsKey(name.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(where + " names unknown backend or group '" + name + "'");
        }
    }

    private static Map<String, List<String>> parseForcedHosts(Config config, Map<String, ServerEntry> servers,
                                                              Map<String, List<String>> groups) {
        Config section = config.get("forced-hosts");
        Map<String, List<String>> hosts = new LinkedHashMap<>();
        if (section == null) {
            return hosts;
        }
        for (Config.Entry entry : section.entrySet()) {
            Object value = entry.getValue();
            List<String> targets = value instanceof String single ? List.of(single) : entry.getValue();
            for (String target : targets) {
                requireDestination(servers, groups, target, "forced-hosts entry '" + entry.getKey() + "'");
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
