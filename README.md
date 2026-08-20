# Relay

A custom Minecraft proxy in Java — a BungeeCord/Velocity alternative built to the design
in [`relay-proxy-dashboard-spec.md`](relay-proxy-dashboard-spec.md).

This repository currently contains **phase 1 (MVP)** of the plan in §10: the protocol
layer, status/login/play relay, configuration, server switching, both forwarding modes,
and plugin-message APIs in both directions. No dashboard, no in-proxy plugin loader —
those are phases 2 and 3.

## Status

| Phase | Scope | State |
|---|---|---|
| 1. MVP | Protocol layer, status/login/play relay, config, `/server`, forwarding modes | **Working** — a real 1.20.2 client joins, plays, and switches servers |
| 2. Dashboard | Javalin backend, React frontend, WebSocket live data, local auth | **Read-only API and a live page working**; auth and the acting half not started |
| 3. Plugins | Annotation + Guice loader, event bus | Not started |
| 4. Hardening | RBAC, Pelican integration, Prometheus, config editor | Permission nodes exist; the rest not started |
| 5. Cutover | Run beside Velocity, migrate | Not started |

A vanilla 1.20.2 client has joined a Paper backend through Relay in online mode, with
modern forwarding, played normally, and moved to a second backend with `/server`. What
has been exercised against real software is the join path — handshake, status,
encryption, Mojang authentication, modern forwarding, configuration, sustained play
traffic in both directions — and the switch path, including the backend-registered
commands that reach the proxy by plugin message. See
[Before you trust it](#before-you-trust-it) for what has not.

## What works

- **Protocol**: Minecraft 1.20.2 – 1.21.8 (protocol 764–772)
- **Status**: server list ping answered from proxy state, MiniMessage MOTD, player sample
- **Login**: online mode with Mojang session auth, offline mode, encryption, compression
- **Forwarding**: modern (Velocity-compatible, HMAC signed), legacy (BungeeCord), none
- **Switching**: `/server` and API-driven, via the 1.20.2+ configuration-phase handover
- **Routing**: ordered fallback list, forced hosts per virtual hostname
- **Groups**: `survival-01` and `survival-02` are a group called `survival` with no
  config at all, balanced by fewest players, round robin, random, or priority order
- **Health checks**: every backend status-pinged on an interval, so a dead one leaves
  routing before a player is sent to it — with hysteresis, so one dropped packet does not
  empty a server
- **Backend load**: TPS, MSPT, heap and CPU pushed by the Relay plugin, because a server
  deep in a GC spiral still answers status pings perfectly well
- **Resilience**: a backend that dies — or kicks everyone on the way down, as a planned
  restart does — moves its players to the next server in the try list rather than off the
  network, so restarting one server is not an outage
- **Commands**: `/server`, `/glist`, `/find`, `/send`, `/drain`, group-aware, with
  permission nodes
- **Draining**: take a backend out of rotation before restarting it, without moving anyone
  already on it — Relay announces the moment it empties
- **Backend API**: BungeeCord-compatible plugin messaging, so existing network plugins
  work unchanged — see [docs/backend-api.md](docs/backend-api.md)
- **Client API**: a plugin-message protocol for client-side mods —
  see [docs/client-api.md](docs/client-api.md)
- **Operations**: backpressure both directions, bounded buffers, graceful shutdown
- **Dashboard**: a live page plus read-only REST and a WebSocket, off by default —
  see [Dashboard API](#dashboard-api)

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

`up` puts every backend **and the proxy** in tabs of a single Windows Terminal window,
navigable with `Ctrl+Tab`. Those tabs are real consoles, not log tails: `stop` and any
other server command can be typed straight into them.

The terminal you launched from is then left free, and becomes a **control console**:

```
relay> status
relay> doctor
relay> ping
relay> shutdown lobby
relay> exit          # servers keep running
```

Every `relay.py` command works there with the same flags, because typed lines are
dispatched through the same parser as the command line — there is one definition of each
command rather than two that can drift. `py relay.py console` opens it against an
already-running stack, and `up --no-shell` skips it.

Individually:

| | |
|---|---|
| `start lobby` | takes over this terminal |
| `start lobby --tabs` | a tab in the shared window (automatic when starting several) |
| `start lobby --console` | a separate window of its own |
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

Balancing is the one feature a single connection cannot judge, so there is a crowd:

```bash
py relay.py fake 40 --offline --switch 5000
```

That connects forty real protocol clients through the proxy, then prints where they
landed:

```
Where they landed
-----------------
  lobby          0
  survival-00   21  ###############
  survival-01   19  #############
  total         40
```

`--switch` keeps them moving between servers rather than joining and sitting still, which
is what exercises the switch path under load; `--offline` flips `online-mode`, runs the
test and puts it back in a `finally`, so an interrupt cannot leave a proxy accepting
anyone who claims a name.

They hold their connections properly: they confirm the join teleport and answer real
keep-alives, which is what a server needs before it considers a player spawned.

They are genuine connections — a proxy cannot tell them from clients — but they measure
**routing, not load**: a fake player never moves or loads a chunk, so a backend holding a
hundred of them is barely working. The proxy must be in offline mode, since nothing here
can authenticate with Mojang; `fake` checks that first and says so rather than letting
forty logins fail identically.

Before blaming anything, check the stack that is actually running:

```bash
py relay.py doctor --live
```

`doctor` alone reads config files. `--live` reads reality: the listener answers a real
ping, the control channel is bound, the dashboard responds, every backend is healthy, and
each backend's installed plugin matches the current build. That last one matters most —
the plugin reports TPS, registers the proxy's commands and carries the backend API, so an
old copy silently disagrees with the proxy about what exists.

One command for the whole rebuild cycle, which waits until the proxy actually answers
rather than until it was merely started:

```bash
py relay.py restart
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

The shaded jar lands in `proxy/build/libs/proxy-<version>.jar`:

```bash
java -jar proxy/build/libs/proxy-0.1.0-SNAPSHOT.jar --config relay.toml
```

## Server groups

Several interchangeable backends can share one name. Usually there is nothing to
configure — numbered backends group themselves:

```toml
[servers]
lobby = "127.0.0.1:25566"
survival-01 = "127.0.0.1:25567"
survival-02 = "127.0.0.1:25568"
```

`survival` now names the group. Players type `/server survival` and land on whichever
member is the better host right now; naming a member directly still works, which is how
someone rejoins the server their base is on. A group is a destination anywhere a backend
is: `try`, forced hosts, `/send`, and both plugin-message APIs.

Only a separator followed by digits counts, so `pvp-arena` stays one server. Write a
`[groups]` block out only to say something the naming does not — an odd set, or members
that are not numbered:

```toml
[groups]
hub = ["lobby", "spawn"]
```

An explicit entry wins over the derived one, and a real backend's name always wins over
a group.

Balancing and failover are deliberately one mechanism rather than two. A group never
answers with a single server; it answers with all of its members, best first. So the
preferred member is tried, and anything that refuses or is unreachable falls through to
the next — and a member that dies under a player hands them to a sibling rather than to
whatever came next in the try list.

| `balance` | |
|---|---|
| `least-players` | fewest players first. Fills an empty server before a busy one, and self-corrects as people come and go. The default |
| `round-robin` | each join takes the next member in turn. Better when players arrive in waves and counts lag the truth |
| `random` | cheap, stateless, even enough over any real number of joins |
| `first-available` | configured order, always. Not balancing: later members exist only to catch failures of earlier ones |

Ties keep configured order in every strategy, so an empty network still fills predictably.

## Dashboard API

Phase 2's read-only half, served by the `relay-dashboard` companion process rather than
by the proxy:

```toml
[control]
enabled = true
bind = "127.0.0.1:25580"

[companions.dashboard]
command = ["java", "-jar", "companions/relay-dashboard.jar"]

[companions.dashboard.environment]
RELAY_DASHBOARD_PORT = "8080"
```

It can also be run by hand against a running proxy, which is what makes it debuggable —
copy the control token from the proxy's startup log:

```bash
RELAY_CONTROL_PORT=25580 RELAY_CONTROL_TOKEN=... java -jar relay-dashboard.jar
```

Open `http://127.0.0.1:8080` for the page itself — live tiles, backends, groups, players
and an event feed. It is bundled in the jar, so the dashboard is one artefact rather than
a proxy plus a web server to deploy beside it, and it has no external dependencies: a
dashboard that needs a CDN to render fails exactly when someone is diagnosing an outage.

| | |
|---|---|
| `GET /api/overview` | players, uptime, backend and group counts, balance strategy |
| `GET /api/servers` | each backend: address, group, health, latency, version, its own player count, and a `load` object with TPS, MSPT, heap and CPU |
| `GET /api/groups` | each group, its members, and its total |
| `GET /api/players` | who is online, which backend they are on, and their full network route |
| `WS /api/events` | one socket carrying every event type |

Events arrive as `{type, player, from, to}`, with `type` one of `PLAYER_CONNECTED`,
`PLAYER_DISCONNECTED`, `PLAYER_SWITCHED_SERVER`, `SERVER_HEALTH_CHANGED`. One socket
multiplexes them all, per
spec §9.4 — a socket per type would multiply connections by the number of things worth
watching.

**Read-only is the boundary, not a stage.** Nothing here moves a player, closes a
connection or edits config, which is what makes it reasonable to run before spec §9.6's
authentication exists. For the same reason player records carry **no IP address**: saying
who is online is one thing, pairing usernames with home addresses on an unauthenticated
port is another. Addresses arrive with the login that guards them.

`load` is null until the Relay plugin reports, and carries `stale` once it stops. The
proxy cannot measure these from outside, and cannot ask for them either: a plugin message
needs a player connection to travel on, so the backend pushes them on a timer. **An empty
backend therefore reports nothing** — its last figures stop being replaced rather than
going to zero, which is why every report is stamped with its age.

Relay warns at startup if you bind it anywhere but loopback. Until there is auth, reach
it over an SSH tunnel.

## Network routes

Spec §9.1 asks the Players view for a player's "complete route", and §7.7 defines a
`routeId` per connection. Most of that shape belongs to the multi-proxy topology of §7,
which is v2 — but the part that is true today answers a question a backend name cannot:

```
mc.example.com → relay-01 → survival → survival-02
```

*Which* server someone is on is easy. *Why that one* is not, and with forced hosts and
groups it is the question that actually gets asked. So a route records the hostname they
connected to, any upstream that announced itself with a PROXY header, this node, the
group that made the choice, and the backend that answered. `survival` deciding and
`survival-02` answering are two different facts; collapsing them would hide the balancing
decision.

It appears in `/api/players`, on the dashboard, and in `/find <player>`. Each session also
carries a short `routeId` — §7.7 issues it for loop prevention across chained proxies,
which is v2, but it is independently the cheapest thing that makes a session greppable:
one token tying every log line, event and dashboard row for one visit together.

The node's own name comes from `node-name`, defaulting to the machine's hostname (§7.9).
One node today makes it only a label, but it is the label routes are read against, and
every node called `relay` would make them useless the day there are two.

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

Joining works. These are the gaps between that and running a network on it.

**Switching is timing-sensitive, and one window is now closed.** A client enters
configuration state the moment it *sends* its acknowledgement, which is a network round
trip before Relay can know. Play packets delivered in that window are decoded against the
wrong state and kill the connection with a decoder exception. Relay now stops forwarding
from the old backend when the request goes out rather than when the reply arrives.

**Only 1.20.2 has been exercised for real.** Every id Relay uses at 1.20.2 is now
confirmed — handshake, status, login and configuration against a live Paper server's own
packet log, the serverbound play ids from live traffic, and the two switch ids by a real
client completing a switch. Every other version in the supported range is still
inference. See
[docs/protocol-ids.md](docs/protocol-ids.md) — the failure modes are deliberately
bounded, and any id can be corrected from `relay.toml` without a rebuild.

**A rescued player rejoins from scratch.** Relay moves connections, not game state
(spec &sect;5.4), so someone whose server died lands in the lobby at its spawn — not
where they were, and with whatever inventory that backend last saved. Recovering more
than the connection is a backend-database problem.

**Single proxy, no persistence.** No bans, whitelist or session history — spec §6.3's
SQLite storage is phase 2, along with the dashboard. Clustering is a non-goal in §2.

**Plugins run unsandboxed** once phase 3 lands, as §5.5 sets out. Nothing to worry about
yet, since there is no plugin loader.

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
├── proxy/          the proxy itself (Java 21) — Netty and nothing web-facing
├── dashboard/      the web dashboard, its own process and its own jar (Java 21)
├── paper-plugin/   Relay, the backend-side plugin (Java 17)
├── docs/           protocol ids, backend and client APIs
└── relay.py        development helper
```

### Why the dashboard is a separate process

Its dependencies — Jetty, Javalin, and the Kotlin runtime Javalin is written in — come to
about 4.6 MB. Relay's own compiled code is about 0.2 MB. None of that belongs in the
process carrying player traffic, where a leak or a crash costs players their session
rather than an operator a page refresh. A Discord bot would be worse: JDA brings a second
HTTP and WebSocket stack that would eventually meet Netty.

So the proxy opens a **control channel** — newline-delimited JSON on loopback, gated by a
token regenerated at every start and handed to companions through their environment — and
Relay starts, supervises and restarts whatever is listed under `[companions]`. Their
output folds into the proxy's log, so one command still starts everything.

The channel is deliberately the smallest thing that could work. Moving the dashboard out
would have bought nothing if the way back had needed its own web server.

### The Relay plugin

The backend-side half. It exposes the proxy's
[backend API](docs/backend-api.md) to a server's plugins — moving players, querying the
network, passing messages between servers — and probes that API on join so a
misconfiguration shows up immediately rather than the first time a plugin needs it.

It also diagnoses connection closes. Paper reports a connection that vanishes as "lost
connection: Disconnected" and logs the real cause at DEBUG, where it is invisible — and
when the pipeline closes a channel before its exception handler runs, nothing is logged
at all. A handler on each player's Netty pipeline closes that gap, reporting:

- whether the **server** or the **client** closed the connection
- a **stack trace of the code that closed it**, which is the part Paper never prints
- kick reasons, caught at lowest priority before another plugin can rewrite them
- writes that failed, which fail on the promise rather than reaching `exceptionCaught`

```bash
py relay.py plugin lobby    # builds it and drops it in that server's plugins folder
```

Then restart the backend. `/relay probe` re-runs the API checks by hand, `/relay connect
<server>` moves you through the proxy, and `/relay forward <text>` exercises cross-server
messaging. `log-packets: true` in its config adds per-packet tracing, which is worth
turning on only for a short targeted session.

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
