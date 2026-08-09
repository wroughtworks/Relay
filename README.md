# Relay

A custom Minecraft proxy in Java — a BungeeCord/Velocity alternative built to the design
in [`relay-proxy-dashboard-spec.md`](relay-proxy-dashboard-spec.md).

This repository currently contains **phase 1 (MVP)** of the plan in §10: the protocol
layer, status/login/play relay, configuration, server switching, and both forwarding
modes. No dashboard, no plugin system — those are phases 2 and 3.

## Status

| Phase | Scope | State |
|---|---|---|
| 1. MVP | Protocol layer, status/login/play relay, config, `/server`, forwarding modes | **Built, untested against a live client** |
| 2. Dashboard | Javalin backend, React frontend, WebSocket live data, local auth | Not started |
| 3. Plugins | Annotation + Guice loader, event bus | Not started |
| 4. Hardening | RBAC, Pelican integration, Prometheus, config editor | Permission nodes exist; the rest not started |
| 5. Cutover | Run beside Velocity, migrate | Not started |

Everything below is verified by the test suite or by running the jar. What is **not**
verified is behaviour against a real Minecraft client — see
[Before you trust it](#before-you-trust-it).

## What works

- **Protocol**: Minecraft 1.20.2 – 1.21.8 (protocol 764–772)
- **Status**: server list ping answered from proxy state, MiniMessage MOTD, player sample
- **Login**: online mode with Mojang session auth, offline mode, encryption, compression
- **Forwarding**: modern (Velocity-compatible, HMAC signed), legacy (BungeeCord), none
- **Switching**: `/server` and API-driven, via the 1.20.2+ configuration-phase handover
- **Routing**: ordered fallback list, forced hosts per virtual hostname
- **Commands**: `/server`, `/glist`, `/find`, `/send`, with permission nodes
- **Operations**: backpressure both directions, bounded buffers, graceful shutdown

## Requirements

- JDK 21+
- Backends running **Paper with `online-mode=false`** and modern forwarding enabled

## Quick start

The `relay.py` helper wraps the whole loop. Python 3.11+, no dependencies:

```bash
py relay.py run
```

That stops any running instance, rebuilds if the jar is stale, and starts the proxy with
debug logging from the project directory — which matters, because `gradle run` uses
`run/` as its working directory and therefore reads a *different* `relay.toml` with a
different generated forwarding secret.

Before blaming the code, run:

```bash
py relay.py doctor
```

It cross-checks Relay's config against each backend's Paper config and reports the
mismatches that otherwise fail silently: a forwarding secret that differs by one
character (compared by hash, so neither is printed), a PROXY protocol setting that agrees
on one side only, a backend left in online mode. Link your backends once so it can see
them:

```bash
py relay.py link lobby /path/to/paper-server
```

Linked backends can also be launched, so the whole stack is one command:

```bash
py relay.py up      # start every backend, wait for their ports, then run the proxy
py relay.py down    # stop all of it
```

`up` gives every backend **its own console window**, so their output can be watched side
by side while this terminal runs the proxy. Those windows are real consoles, not log
tails: `stop` and any other server command can be typed straight into them.

Individually:

| | |
|---|---|
| `start lobby` | takes over this terminal |
| `start lobby --console` | its own window (automatic when starting several) |
| `start lobby --background` | detached, logging to `.relay-run/`, nothing to type into |
| `start lobby --debug` | adds Paper's packet logging |

Stopping a server with no console — one started `--background` — needs RCON. Windows has
no SIGTERM, so a detached Java process cannot be signalled cleanly, and a hard kill skips
Paper's shutdown hook and can lose recently generated chunks. `shutdown` therefore uses
RCON where it is enabled and otherwise refuses, explaining the options rather than
silently risking the world. Enabling it in `server.properties` is worth doing:

```properties
enable-rcon=true
rcon.port=25575
rcon.password=something
```

Other commands: `status`, `ping` (a real server-list ping, proving the proxy answers
rather than merely listens), `build`, `stop`, `logs -f`, `test`, and `paper-debug <name>`
which writes the log4j2 config that reveals Paper's packet-level logging.

## Quick start (Gradle directly)

```bash
./gradlew run
```

First run writes a commented `relay.toml` into `run/`, with a freshly generated
forwarding secret, then exits with a config error until you point `[servers]` at real
backends.

Build a standalone jar:

```bash
./gradlew build
```

The shaded jar lands in `build/libs/relay-<version>.jar`:

```bash
java -jar build/libs/relay-0.1.0-SNAPSHOT.jar --config relay.toml
```

## Configuring backends

Every backend must have `online-mode=false` in `server.properties` — the proxy has
already authenticated the player. Then, for modern forwarding, in Paper's
`config/paper-global.yml`:

```yaml
proxies:
  velocity:
    enabled: true
    online-mode: true
    secret: <the forwarding-secret from relay.toml>
```

Relay's modern forwarding is wire-compatible with Velocity's, so backends already
configured for Velocity need no change.

Geyser is an ordinary backend entry. Relay does not speak Bedrock itself; the Geyser
bridge does, exactly as it does behind Velocity today.

## Docker

```bash
docker compose up -d --build
```

Config, logs and the forwarding secret live in `./data`. The compose file publishes
25577 rather than 25565 so it can run beside an existing Velocity for side-by-side
testing, which is the cutover path in spec §10.

## Testing

```bash
./gradlew test
```

Covers wire-format primitives, the NBT component writer, the codec stack under
`EmbeddedChannel` (framing, compression, encryption, packet round trips), the packet
registry and its override mechanism, config parsing and validation, permission
resolution, and the forwarding signature. `ProxyPingTest` starts a real listener and
drives it over a real socket, speaking the protocol by hand so a symmetric encode/decode
bug cannot pass.

## Before you trust it

Two things are worth knowing before this goes anywhere near players.

**Packet ids are unverified.** Relay depends on about twenty packet ids. Those in
handshake, status, login and configuration state are stable and confident. The five
play-state ids are the ones that move whenever Mojang inserts a packet, and they have
not been checked against a live client. The failure modes are deliberately bounded — see
[docs/protocol-ids.md](docs/protocol-ids.md) — and any id can be corrected from
`relay.toml` without a rebuild. Verify them before a production cutover.

**Nothing has been run against a real Minecraft client.** The tests prove the codecs are
self-consistent and that the proxy answers a socket correctly. They cannot prove a
vanilla client is happy with what comes back. The first real test is: join, switch
servers with `/server`, and confirm chunks load on the far side.

## Design notes

Three decisions worth knowing, all of them narrowing scope on purpose:

**1.20.2 is the floor.** That is where the configuration phase was introduced. Every
version at or above it shares one connection model, so server switching has exactly one
implementation instead of two. Supporting anything older would mean carrying the
pre-configuration "fake respawn" dance forever — the scope trap spec §11 opens with.

**Only registered packets are parsed.** Everything else is forwarded as an opaque frame,
never copied or allocated for. Clientbound play packets are registered *write-only*, so
the direction carrying the overwhelming majority of bytes has no packet-id dependency at
all and cannot break on a Minecraft update.

**Connection handoff only.** Per spec §5.4, inventory and game state across backends is a
backend-database problem. Relay moves connections and nothing else.

## Modules

```
relay/
├── proxy/          the proxy itself (Java 21)
├── paper-plugin/   RelayDebug, a backend-side diagnostic plugin (Java 17)
├── docs/           protocol ids, client API
└── relay.py        development helper
```

### RelayDebug

Paper reports a connection that vanishes as "lost connection: Disconnected" and logs the
real cause at DEBUG, where it is invisible — and when the pipeline closes a channel before
its exception handler runs, nothing is logged at all. RelayDebug closes that gap by
installing a handler on each player's Netty pipeline. It reports:

- whether the **server** or the **client** closed the connection
- a **stack trace of the code that closed it**, which is the part Paper never prints
- kick reasons, caught at lowest priority before another plugin can rewrite them
- writes that failed, which fail on the promise rather than reaching `exceptionCaught`

```bash
py relay.py plugin lobby    # builds it and drops it in that server's plugins folder
```

Then restart the backend and reproduce. `log-packets: true` in its config adds per-packet
tracing, which is worth turning on only for a short targeted session.

The plugin finds the channel by searching the player object for a field of type
`io.netty.channel.Channel` rather than following a named path — field names are remapped
between Minecraft versions and differ between Spigot-mapped and Mojang-mapped builds, so a
type-based search survives upgrades that a name-based one would not.

## Layout

```
dev.relay
├── protocol/          wire format, packet definitions, id registry
│   ├── packet/        the ~20 packets Relay understands
│   └── nbt/           JSON → network NBT, for 1.20.3+ text components
├── net/               Netty pipeline, connection state machine, crypto
│   └── pipeline/      framing, compression, encryption, packet codecs
├── session/           per-state handlers for both player and backend connections
├── proxy/             the proxy, players, backends, connection results
├── forwarding/        modern (HMAC) and legacy (hostname) schemes
├── auth/              Mojang session authentication, game profiles
├── command/           /server, /glist, /find, /send, permission nodes
└── config/            relay.toml parsing and validation
```

The trickiest code is the server switch, spread across `ClientPlaySessionHandler`,
`ClientConfigSessionHandler` and `BackendConfigSessionHandler`. The sequence is
documented at the top of `BackendConfigSessionHandler`.

## Not built yet

Deliberately out of scope for phase 1, all named in the spec:

- Web dashboard (phase 2) — no Javalin, no REST, no WebSocket
- Plugin system (phase 3) — no `@Plugin`, no Guice, no event bus
- SQLite storage — no bans, whitelist or session history yet
- Prometheus metrics, Pelican Panel integration (phase 4)
- Proxy clustering — explicitly a non-goal in §2
