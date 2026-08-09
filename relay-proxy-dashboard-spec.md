# Relay — Minecraft Proxy + Web Dashboard

Design spec for a custom BungeeCord/Velocity-alternative proxy with a built-in
web dashboard. "Relay" is a placeholder name — swap it for whatever you land on.

## 1. Overview

- **Goal**: custom Minecraft proxy in Java, with a native web dashboard for
  live monitoring and control — no bolted-on third-party panel.
- **Target use**: drop-in front door for your existing network (Paper
  backends, Geyser bedrock bridge, currently behind Velocity).
- **v1 scope**: single proxy instance, N backend servers, dashboard for
  monitoring + basic control. No multi-proxy clustering in v1.

## 2. Non-Goals (v1)

- No proxy clustering / horizontal scaling
- No plugin marketplace or hot-reload plugin system (v2)
- No reimplementing Bedrock protocol — Geyser stays a backend server, not
  something Relay speaks natively

## 3. Tech Stack At a Glance

| Component | Choice |
|---|---|
| Core proxy | Java + Netty |
| Protocol codec | Custom Netty pipeline; reference MCProtocolLib for a head start on packet definitions |
| Plugin system | Native Java — annotation discovery + Guice DI + per-plugin classloader (Velocity-style, trusted execution) |
| Config format | Plain TOML (toml4j / night-config) for v1 — see §5.6 on TOML+ |
| Dashboard backend | Javalin (embeds Jetty, thin, easy same-JVM WebSocket + REST) |
| Dashboard frontend | React + TypeScript |
| Storage | SQLite via JDBC (`sqlite-jdbc`) |
| Metrics | Micrometer → Prometheus registry |
| Logging | SLF4J + Logback |
| Auth | Local accounts (v1); AuthCentral integration (v2 option) |
| Build | Gradle |

## 4. Architecture

```
                    ┌───────────────────────────────┐
                    │             Relay              │
Minecraft   ──TCP──▶│  Protocol Layer (Netty pipeline)│
Clients             │             │                   │
(Java + Bedrock     │  Router / Session Manager       │
 via Geyser)        │             │                   │
                    │  Backend Connection Pool ───────┼──────▶ Paper / Geyser
                    │             │                   │        servers
                    │  Event Bus / Plugin API (Java)  │
                    │             │                   │
                    │  Dashboard API (Javalin: REST+WS)┼──WS──▶ Web Dashboard
                    └───────────────────────────────┘        (React/TS)
```

## 5. Core Proxy

### 5.1 Protocol support
Target a version *range*, not "all versions." Pin to the latest 2–3 MC
major releases at launch; add ViaVersion-style translation later only if you
actually need it. Full multi-version support is the single biggest scope trap
in a proxy project — it's most of what took Velocity/BungeeCord years to
mature. MCProtocolLib is worth reading even if you don't depend on it
directly, purely for packet-layout reference.

### 5.2 Connection lifecycle
Handshake → Status (MOTD/ping) or Login → encryption + compression
negotiation → forwarding handshake → Play (bidirectional relay with
server-switch interception on respawn/join-game packets).

### 5.3 Forwarding modes
- Modern forwarding (Velocity-style signed player info) — primary
- Legacy BungeeCord forwarding — compatibility fallback
- None — local/offline testing

### 5.4 Server switching
`/server <name>` and API-driven switches. Scope boundary: the proxy handles
connection handoff only — inventory/game-state sync across servers is a
backend-DB problem, not Relay's job. Keep that line firm or this balloons.

### 5.5 Plugin API
- v1: native Java plugin system, mirroring Velocity's model since that's
  what anyone writing a plugin for this will already expect
- Annotation-based discovery (`@Plugin`) + Guice for dependency injection —
  low surprise for Velocity plugin devs
- Per-plugin classloader to avoid classpath collisions between plugins, same
  technique Bukkit/Velocity use
- **Trust model**: plugins run in the same JVM with full permissions — no
  sandboxing. Java's `SecurityManager`, the traditional sandboxing mechanism,
  is deprecated for removal in modern JDKs, so don't design around it. Real
  plugin sandboxing (separate process, restricted classloader policy, etc.)
  is a v2+ effort, not something the JDK gives you for free.

### 5.6 Config
Plain TOML for v1 (`toml4j` or `night-config`) rather than TOML+. Your TOML+
tooling currently exists for Python and TypeScript — there's no Java parser
yet. Two paths: (a) write one, which would also serve Rivet's Java target, or
(b) use plain TOML now and revisit once a Java TOML+ parser exists. Recommend
(b) so proxy work doesn't block on parser work.

### 5.7 Commands + permissions
In-proxy commands (`/glist`, `/send`, `/find`) with a permission-node system
shared with dashboard RBAC (§6.5) — one source of truth, not two.

## 6. Web Dashboard

### 6.1 Views
- **Overview** — players online (total + per-backend), uptime, throughput
- **Servers** — per-backend status/TPS, start/stop/restart hooked into
  Pelican Panel's API rather than reimplementing process control, since your
  servers are already Pelican-managed
- **Players** — live list, search, kick/ban/whitelist, connection history
- **Console** — streamed proxy log, optionally backend logs if Pelican
  exposes them over its own API/WS
- **Config editor** — edit the TOML config in-browser. Schema validation
  would map directly onto TOML+ annotations (`@required`, `@enum`,
  `@min`/`@max`) if and when you build the Java parser; on plain TOML for v1
  you'd hand-roll validation instead
- **Metrics** — time-series charts (players online, packets/sec, bandwidth)
  off the Prometheus endpoint, or point Grafana at it if you already run one

### 6.2 Real-time channel
Single WebSocket connection (Javalin's built-in WS support) multiplexing
player-count, TPS, console lines, and (optionally) chat — avoid one socket
per data type.

### 6.3 Storage
SQLite via JDBC for bans/whitelist/session history. Config stays in the TOML
file, not the DB — single source of truth, git-able.

### 6.4 Auth
Local accounts for v1 — `argon2-jvm` for password hashing, `jjwt` for session
tokens. Worth wiring in AuthCentral instead of building auth a second time —
you already have a working OAuth broker pattern from the Notion desktop app.

### 6.5 RBAC
Roles: admin / moderator (player actions only) / viewer (read-only). Same
permission nodes drive both in-game commands and dashboard buttons.

## 7. Observability
SLF4J + Logback for logs → stdout and dashboard console stream from the same
source. Micrometer feeding a Prometheus meter registry: connections,
packets/sec, backend health, WS clients connected.

## 8. Security
- TLS terminated in front of the dashboard (Caddy/nginx in your homelab)
- Rate-limit login attempts and API endpoints
- Validate every packet at the protocol layer before relaying — the proxy is
  your internet-facing surface; backends should never see malformed input
- Forwarding-secret rotation for modern forwarding

## 9. Deployment
Shaded/fat JAR (Gradle Shadow plugin) + Docker image on an OpenJDK base —
fits your TrueNAS/Docker homelab pattern directly. Config + SQLite in a
mounted volume. Run it alongside your existing Velocity/Paper stack for
side-by-side testing before cutover.

## 10. Phased Build Plan
1. **MVP** — protocol layer, status/login/play relay, static config,
   `/server` switching, both forwarding modes. No dashboard, no plugins.
2. **Dashboard v1** — Javalin backend, overview/servers/players views, WS
   live data, local auth.
3. **Plugin system** — annotation + Guice plugin loader, event bus, one
   first-party plugin to validate the API surface.
4. **Hardening** — RBAC, Pelican integration, Prometheus metrics, config
   editor with validation.
5. **Cutover** — run alongside Velocity, migrate once stable.

## 11. Risks to Flag Now
- **Protocol version scope** is the classic time sink for proxy projects —
  pin a narrow range for v1 or this never ships.
- **Plugin trust model** — same-JVM, no sandbox. A buggy or malicious plugin
  can crash or compromise the whole proxy. Document this clearly before
  anyone besides you installs a plugin.
- **Geyser interop** — confirm modern forwarding behaves correctly through
  the Bedrock bridge before assuming Java-only forwarding logic is portable.
- **Console streaming at scale** — fine for a homelab-sized deployment; don't
  over-engineer a pub/sub log system you don't need yet.
- **TOML+ Java gap** — no Java parser exists yet. Budget time for one if you
  want config parity with your other tools, or accept plain TOML for v1.
