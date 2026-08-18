# Relay — Minecraft Proxy + Web Dashboard

Design spec for a custom BungeeCord/Velocity-alternative proxy with a built-in
web dashboard and a path toward large-scale distributed Minecraft network
management.

"Relay" is a placeholder name — swap it for whatever you land on.

## 1. Overview

* **Goal**: custom Minecraft proxy in Java, with a native web dashboard for
  live monitoring and control — no bolted-on third-party panel.
* **Target use**: drop-in front door for an existing network using Paper
  backends, Geyser Bedrock bridges, and currently sitting behind something
  such as Velocity.
* **Long-term goal**: grow Relay from a single proxy into a distributed
  Minecraft network platform capable of managing proxy clusters, backend
  pools, load balancing, health checks, failover, and regional routing.
* **v1 scope**: single proxy instance, N backend servers, dashboard for
  monitoring + basic control.
* **v2+ scope**: multiple Relay proxies capable of routing through one
  another, centralized topology management, proxy/backend pools, and
  horizontal scaling.

Relay should eventually be thought of less as simply a proxy and more as a
Minecraft network control platform.

---

## 2. Non-Goals (v1)

* No proxy clustering / horizontal scaling in the first release
* No proxy-to-proxy chaining in the first release
* No automatic infrastructure scaling in the first release
* No plugin marketplace or hot-reload plugin system
* No reimplementing Bedrock protocol — Geyser stays a backend server, not
  something Relay speaks natively
* No attempt to provide Kubernetes-style orchestration in v1

The important constraint is that the v1 architecture should **not prevent**
these features from being introduced later.

---

## 3. Tech Stack At a Glance

| Component             | Choice                                                                 |
| --------------------- | ---------------------------------------------------------------------- |
| Core proxy            | Java + Netty                                                           |
| Protocol codec        | Custom Netty pipeline; reference MCProtocolLib for packet definitions  |
| Plugin system         | Native Java — annotation discovery + Guice DI + per-plugin classloader |
| Config format         | Plain TOML (`toml4j` / `night-config`) for v1                          |
| Dashboard backend     | Javalin                                                                |
| Dashboard frontend    | React + TypeScript                                                     |
| Storage               | SQLite via JDBC (`sqlite-jdbc`)                                        |
| Metrics               | Micrometer → Prometheus registry                                       |
| Logging               | SLF4J + Logback                                                        |
| Auth                  | Local accounts (v1); AuthCentral integration optional later            |
| Build                 | Gradle                                                                 |
| Cluster communication | Custom authenticated Relay node protocol — v2+                         |
| Node discovery        | Control-plane registry/static configuration initially — v2+            |

---

# 4. Architecture

## 4.1 v1 Architecture

```text
                    ┌────────────────────────────────┐
                    │             Relay              │
Minecraft   ──TCP──▶│ Protocol Layer (Netty pipeline)│
Clients             │              │                 │
                    │ Router / Session Manager       │
Java + Bedrock      │              │                 │
via Geyser          │ Backend Connection Pool ───────┼────▶ Paper / Geyser
                    │              │                 │      servers
                    │ Event Bus / Plugin API         │
                    │              │                 │
                    │ Dashboard API                  │
                    │ Javalin REST + WebSocket       │
                    └──────────────┬─────────────────┘
                                   │
                                   ▼
                              Web Dashboard
                               React / TS
```

v1 contains only one Relay proxy, but several concepts should already use
abstractions that can later support multiple nodes.

For example, avoid tightly coupling routing decisions to a specific backend
server instance.

Instead of thinking only in terms of:

```text
Player → survival-01
```

internally allow the routing layer to eventually support:

```text
Player → survival pool → survival-01
```

---

## 4.2 Long-Term Distributed Architecture

Relay should eventually separate the network into two major concepts:

* **Data Plane**
* **Control Plane**

```text
                       CONTROL PLANE

                 ┌──────────────────────┐
                 │    Web Dashboard     │
                 └──────────┬───────────┘
                            │
                 ┌──────────▼───────────┐
                 │   Relay Controller   │
                 │                      │
                 │ Topology             │
                 │ Service Discovery    │
                 │ Health State         │
                 │ Routing Policies     │
                 │ Configuration        │
                 │ Metrics Aggregation  │
                 └──────────┬───────────┘
                            │
                   configuration/state
                            │
────────────────────────────┼────────────────────────────

                         DATA PLANE

Players ──▶ Relay ──▶ Relay ──▶ Backend
Players ──▶ Relay ────────────▶ Backend
Players ──▶ Relay ──▶ Relay ──▶ Relay ──▶ Backend
```

The control plane determines **how the network should operate**.

The data plane actually forwards Minecraft connections.

A critical requirement is:

> Existing player connections and normal routing should continue functioning
> when the dashboard or control-plane service becomes temporarily
> unavailable.

Relay nodes should retain enough local state to operate independently during
temporary control-plane outages.

---

# 5. Core Proxy

## 5.1 Protocol Support

Target a version *range*, not "all versions."

Pin to the latest 2–3 Minecraft major releases at launch; add
ViaVersion-style translation later only if actually needed.

Full multi-version support is one of the largest scope traps in a proxy
project.

MCProtocolLib is worth studying even if Relay does not depend on it directly,
purely for packet-layout and state-machine reference.

---

## 5.2 Connection Lifecycle

```text
Handshake
   ↓
Status or Login
   ↓
Encryption
   ↓
Compression
   ↓
Player Forwarding
   ↓
Routing Decision
   ↓
Backend Connection
   ↓
Play
```

Play mode consists primarily of bidirectional packet forwarding with Relay
intercepting packets when functionality such as server switching requires it.

---

## 5.3 Forwarding Modes

* Modern forwarding — Velocity-style signed player information
* Legacy BungeeCord forwarding — compatibility fallback
* None — local/offline testing
* Relay node forwarding — v2+

Relay-to-Relay communication should eventually use Relay's own authenticated
forwarding mechanism rather than pretending another Relay node is a normal
Minecraft backend.

---

## 5.4 Server Switching

Support:

```text
/server <name>
```

and API-driven server switches.

The proxy handles connection handoff only.

Inventory synchronization, economy synchronization, game state, and similar
cross-server information remain backend/application responsibilities.

---

## 5.5 Plugin API

* Native Java plugin system
* Annotation-based discovery using `@Plugin`
* Guice dependency injection
* Per-plugin classloader
* Event-driven API
* Async APIs where appropriate
* Routing API
* Player API
* Server API
* Eventually cluster/proxy APIs

Example future API:

```java
relay.players()
    .find(playerId)
    .sendToGroup("survival");
```

rather than requiring a plugin to identify `survival-04` itself.

### Trust Model

Plugins run inside Relay's JVM with full process permissions.

There is no Java SecurityManager-based sandbox.

A malicious or poorly written plugin can potentially:

* crash Relay
* access files
* consume excessive CPU
* consume excessive memory
* interfere with other plugins

Real plugin isolation would require a substantially different execution model
and belongs in a later version.

---

## 5.6 Config

Use plain TOML for v1 with `toml4j` or `night-config`.

TOML+ can be introduced once Java tooling exists.

Configuration should eventually support concepts such as:

```toml
[[servers]]
name = "survival-01"
address = "10.0.10.21:25565"
group = "survival"

[[servers]]
name = "survival-02"
address = "10.0.10.22:25565"
group = "survival"
```

Later:

```toml
[[proxy_pools]]
name = "survival-proxies"
strategy = "least-connections"

[[backend_pools]]
name = "survival"
strategy = "least-players"
```

---

## 5.7 Commands + Permissions

Initial commands:

```text
/server
/glist
/send
/find
```

Later:

```text
/proxy
/network
/drain
/routes
/pool
```

A shared permission-node system should power both:

* in-game command authorization
* dashboard RBAC

There should be one authorization model, not two unrelated systems.

---

# 6. Routing and Load Balancing

Load balancing should become one of Relay's primary differentiators.

## 6.1 Backend Pools

Rather than applications always targeting a concrete server:

```text
survival-03
```

they should optionally target:

```text
Pool: survival
```

Example:

```text
              Survival Pool

             ┌───────────────┐
             │ Load Balancer │
             └───────┬───────┘
                     │
          ┌──────────┼──────────┐
          ▼          ▼          ▼
    survival-01 survival-02 survival-03
       72/100      91/100      34/100
                                  ▲
                            selected server
```

This allows backend instances to be added and removed without changing every
plugin that references them.

---

## 6.2 Load-Balancing Strategies

Relay should eventually provide multiple strategies.

### Least Players

Route to the backend containing the fewest players.

### Least Connections

Particularly useful for proxy pools.

### Round Robin

Rotate sequentially through healthy nodes.

### Weighted

Example:

```text
survival-01 weight=100
survival-02 weight=100
survival-03 weight=50
```

Useful when machines have different hardware capabilities.

### Resource Aware

Future option based on:

* CPU load
* memory usage
* TPS
* MSPT
* player count
* network usage

### Latency Aware

Potentially select nodes according to player/network latency.

### Custom

Plugins should eventually be capable of implementing custom routing
strategies.

---

## 6.3 Health Checks

Every managed backend should have a health state.

Example:

```text
HEALTHY
DEGRADED
DRAINING
UNHEALTHY
OFFLINE
UNKNOWN
```

Health checks could include:

* TCP connectivity
* Minecraft status response
* Relay Agent heartbeat
* TPS
* MSPT
* memory
* CPU
* manual administrator state

Unhealthy nodes should automatically be removed from new routing decisions.

---

## 6.4 Failover

Example:

```text
survival-01
     │
     X health check failure
     │
     ▼
Removed from pool
     │
     ▼
New players routed to
survival-02 / survival-03
```

Relay should distinguish between:

* **new connection failover**
* **existing connection failure**

Moving an already-connected player between failed backend servers is much
more complicated and should be treated separately from simply preventing new
connections from reaching an unhealthy server.

---

## 6.5 Draining

Operators should be able to put nodes into a `DRAINING` state.

```text
ACTIVE
  ↓
DRAINING
  ↓
0 players
  ↓
SAFE TO RESTART
```

While draining:

* existing players remain connected
* new players stop being assigned
* dashboard displays remaining connections
* optional automatic shutdown/restart occurs at zero players

This makes rolling maintenance significantly easier.

---

# 7. Multi-Proxy Architecture — v2+

## 7.1 Proxy Chaining

Relay should eventually support another Relay proxy as a routing target.

```text
Player
  │
  ▼
Edge Relay
  │
  ▼
Service Relay
  │
  ▼
Backend
```

This should be a first-class Relay feature rather than simply pretending the
second proxy is an ordinary Minecraft server.

---

## 7.2 Hierarchical Proxy Topology

A large network could look like:

```text
                          Internet
                             │
                    ┌────────▼────────┐
                    │   Edge Layer    │
                    └────────┬────────┘
                             │
             ┌───────────────┼───────────────┐
             │               │               │
             ▼               ▼               ▼
        Lobby Relay     Survival Relay   Minigame Relay
             │               │               │
             ▼               ▼               ▼
         Backends         Backends         Backends
```

This provides:

* horizontal scaling
* failure isolation
* service separation
* independent maintenance
* easier capacity planning

---

## 7.3 Proxy Pools

Proxy instances should be groupable just like backend servers.

Example:

```text
survival-proxies
├── survival-proxy-01
├── survival-proxy-02
├── survival-proxy-03
└── survival-proxy-04
```

An edge node can then route toward:

```text
Pool: survival-proxies
```

instead of:

```text
survival-proxy-03
```

---

## 7.4 Multi-Level Load Balancing

Routing can occur at several layers.

```text
Player
   │
   ▼
Edge Pool
   │
   ▼
Service Proxy Pool
   │
   ▼
Backend Pool
   │
   ▼
Minecraft Server
```

Example:

```text
Player
   │
   ▼
edge-us-east
   │
   ▼
skyblock-proxies
   │
   ▼
skyblock-backends
   │
   ▼
skyblock-17
```

Each level may use a different balancing algorithm.

---

## 7.5 Regional Routing

A large deployment may operate multiple entry regions.

```text
                       Global Entry
                            │
             ┌──────────────┼──────────────┐
             │              │              │
             ▼              ▼              ▼
          US East         US West          Europe
             │              │              │
             ▼              ▼              ▼
        Relay Pool     Relay Pool      Relay Pool
```

The network could select an entry point based on external DNS/load-balancing
infrastructure.

Once inside Relay, routing decisions can take region into account.

Possible future metadata:

```text
preferredRegion = us-east
fallbackRegion = us-west
```

---

## 7.6 Relay Node Protocol

Relay-to-Relay traffic should eventually use an authenticated internal
protocol.

Metadata may include:

```json
{
  "playerId": "UUID",
  "username": "Carson",
  "sourceNode": "edge-us-east-01",
  "targetGroup": "survival",
  "routeId": "82afc111",
  "hopCount": 2,
  "maxHops": 5
}
```

The internal protocol can also carry:

* authenticated player identity
* original client address
* original virtual host
* routing information
* tracing information
* network metadata
* capability negotiation
* protocol version
* node ID

---

## 7.7 Routing Loop Prevention

Proxy chaining introduces the possibility of loops.

Example:

```text
Proxy A
  ↓
Proxy B
  ↓
Proxy C
  ↓
Proxy A
```

Relay must prevent this.

Each routed connection should have:

```text
routeId
hopCount
maxHops
visitedNodes
```

Example:

```text
routeId: 82afc111
hopCount: 2
maxHops: 5
visitedNodes:
  - edge-01
  - survival-proxy-02
```

Relay must terminate routing if:

```text
hopCount >= maxHops
```

or if the current node already appears in `visitedNodes`.

The control plane should also detect invalid cyclic topology before the route
is deployed.

---

## 7.8 Node Identity

Every Relay node should eventually possess a stable identifier.

Example:

```text
relay-us-east-edge-01
relay-us-east-edge-02
relay-survival-01
```

Node identity is separate from hostname/IP.

This allows addresses to change without breaking cluster identity.

---

## 7.9 Node Capabilities

Different Relay nodes may expose different roles.

Examples:

```text
EDGE
ROUTER
SERVICE_PROXY
CONTROL
HYBRID
```

A node may advertise capabilities such as:

```text
minecraft-routing
player-authentication
bedrock-entry
service-routing
dashboard-api
metrics
```

This creates room for more specialized deployments later.

---

# 8. Service Discovery

Large networks should not require every proxy to contain a manually updated
list of every backend.

Relay should eventually maintain a service registry.

Example:

```text
survival
├── survival-01    HEALTHY
├── survival-02    HEALTHY
├── survival-03    DRAINING
└── survival-04    OFFLINE
```

When a server joins:

```text
Server starts
     ↓
Registers
     ↓
Health check succeeds
     ↓
Added to routing pool
```

When it fails:

```text
Health checks fail
     ↓
Marked UNHEALTHY
     ↓
Removed from new routing
```

For early versions this registry can be supplied through configuration.

Dynamic discovery can come later.

---

# 9. Web Dashboard

## 9.1 Views

### Overview

Show:

* total players
* players per backend
* players per proxy
* connections/sec
* packets/sec
* network bandwidth
* active nodes
* unhealthy nodes
* uptime

### Servers

Per-backend information:

* status
* players
* TPS
* MSPT
* CPU
* memory
* assigned pool
* drain state

Start/stop/restart operations should integrate with Pelican rather than
reimplementing process management.

### Proxies — v2+

Display:

* node health
* active players
* current connections
* region
* role
* CPU
* memory
* network throughput
* uptime
* software version
* draining state

### Players

Live player list with:

* search
* current backend
* current proxy
* complete route
* ping
* kick
* ban
* whitelist
* connection history

Example:

```text
Carson

Route:
edge-us-east-01
    ↓
survival-proxy-03
    ↓
survival-07
```

### Console

Stream Relay logs.

Backend console access should use Pelican's existing API/WebSocket support
where available.

### Config Editor

Edit TOML configuration from the browser.

Future TOML+ schema support could provide validation from annotations such as:

```text
@required
@enum
@min
@max
```

### Metrics

Time-series charts for:

* players
* packet rate
* connections/sec
* bandwidth
* routing latency
* backend latency
* node health
* failovers
* server-switch operations

---

## 9.2 Network Topology View — v2+

One of the dashboard's major features should be an interactive topology map.

Example:

```text
Internet
   │
   ├── edge-01 ──┬── survival-proxy-01 ──┬── survival-01
   │             │                       ├── survival-02
   │             │                       └── survival-03
   │             │
   │             └── minigame-proxy-01 ──┬── bedwars-01
   │                                     └── bedwars-02
   │
   └── edge-02
```

Nodes should visually expose:

* health
* player count
* connection count
* traffic
* route relationships
* capacity

Operators should eventually be able to inspect an individual player's route
through the topology.

---

## 9.3 Pool Management

The dashboard should allow administrators to manage pools.

Example:

```text
Survival Backends

survival-01      HEALTHY      72 players
survival-02      HEALTHY      81 players
survival-03      DRAINING     14 players
survival-04      OFFLINE       0 players
```

Actions:

```text
Drain
Enable
Disable
Restart
Change Weight
Move Pool
```

---

## 9.4 Real-Time Channel

Use one Javalin WebSocket connection and multiplex event types.

Possible events:

```text
PLAYER_CONNECTED
PLAYER_DISCONNECTED
PLAYER_SWITCHED_SERVER
SERVER_HEALTH_CHANGED
PROXY_HEALTH_CHANGED
NODE_REGISTERED
NODE_REMOVED
ROUTE_CHANGED
METRIC_UPDATE
CONSOLE_LINE
```

Avoid one WebSocket per type.

---

## 9.5 Storage

SQLite via JDBC for v1:

* bans
* whitelist
* local accounts
* session history
* audit events

Configuration stays in TOML.

For a distributed deployment, SQLite should **not** become the network-wide
shared state database.

Distributed state/storage is a separate v2+ architectural decision.

---

## 9.6 Auth

Local accounts for v1.

Suggested:

```text
argon2-jvm
jjwt
```

AuthCentral can be integrated later rather than maintaining multiple unrelated
authentication systems.

---

## 9.7 RBAC

Initial roles:

```text
admin
moderator
viewer
```

Eventually allow custom roles.

Permissions should control both dashboard and in-game operations.

Examples:

```text
relay.player.kick
relay.player.ban
relay.server.restart
relay.server.drain
relay.proxy.drain
relay.network.routes.view
relay.network.routes.modify
relay.config.edit
```

---

# 10. Observability

Use:

```text
SLF4J
   ↓
Logback

Micrometer
   ↓
Prometheus
```

Metrics should eventually include:

### Connections

```text
relay_connections_active
relay_connections_total
relay_connections_failed
```

### Players

```text
relay_players_online
relay_player_switches_total
```

### Traffic

```text
relay_packets_received
relay_packets_sent
relay_bytes_received
relay_bytes_sent
```

### Routing

```text
relay_route_decisions_total
relay_route_failures_total
relay_route_hops
relay_failovers_total
```

### Backends

```text
relay_backend_health
relay_backend_players
relay_backend_latency
```

### Proxy Cluster

```text
relay_node_health
relay_node_connections
relay_node_players
relay_node_latency
```

---

## 10.1 Distributed Tracing — Future

The `routeId` concept can eventually provide tracing.

Example:

```text
Player connection
Route ID: 82afc111

edge-us-east-02
     │ 3 ms
     ▼
survival-proxy-01
     │ 1 ms
     ▼
survival-07
```

This would be extremely useful for diagnosing large-network routing
problems.

---

# 11. Security

## 11.1 Client-Facing Security

* Validate incoming packets
* Reject malformed packets before they reach backends
* Connection rate limiting
* Login rate limiting
* Packet size limits
* Handshake validation
* Forwarding-secret rotation

Relay is internet-facing infrastructure and should be treated accordingly.

---

## 11.2 Dashboard Security

Terminate TLS through:

```text
Caddy
nginx
```

or equivalent infrastructure.

Include:

* password hashing
* short-lived sessions/tokens
* API authorization
* RBAC
* login throttling
* audit logging
* CSRF protection where applicable

---

## 11.3 Inter-Proxy Security — v2+

Relay nodes must never blindly trust another node simply because it can reach
the internal port.

Possible authentication:

```text
mutual TLS
```

or another cryptographically authenticated node identity mechanism.

Relay-to-Relay communication should verify:

* node identity
* cluster membership
* protocol version
* authentication credentials
* message integrity
* allowed capabilities

Original client IP/player identity metadata should only be trusted when
received from an authenticated Relay node.

---

# 12. Deployment

v1:

```text
Relay.jar
config/
relay.toml
relay.db
plugins/
```

Produce:

* shaded/fat JAR
* Docker image
* OpenJDK base image

Config and SQLite should live in mounted volumes.

Relay can initially run alongside the existing Velocity/Paper stack for
side-by-side testing.

---

## 12.1 Large Deployment Example

```text
                         mc.example.net
                               │
                 External Load Balancer / DNS
                               │
                ┌──────────────┴──────────────┐
                │                             │
                ▼                             ▼
           edge-01                         edge-02
                │                             │
        ┌───────┼────────┐            ┌───────┼────────┐
        │       │        │            │       │        │
        ▼       ▼        ▼            ▼       ▼        ▼
      Lobby Survival Minigames      Lobby Survival Minigames
      Proxy   Proxy    Proxy        Proxy   Proxy    Proxy
        │       │        │            │       │        │
        ▼       ▼        ▼            ▼       ▼        ▼
      Pools   Pools    Pools        Pools   Pools    Pools
```

---

# 13. Automatic Scaling — Future

Relay should not initially become an infrastructure orchestrator.

However, its architecture should allow external systems to react to Relay
metrics.

Example:

```text
SkyBlock proxy capacity > 80%
              │
              ▼
       Scaling controller
              │
              ▼
       Start new proxy
              │
              ▼
     Proxy registers with Relay
              │
              ▼
      Health check succeeds
              │
              ▼
       Added to proxy pool
```

This could eventually integrate with systems such as:

* Docker
* Pelican
* Pterodactyl
* Kubernetes
* custom infrastructure APIs

Relay provides network state; the infrastructure platform provides compute.

---

# 14. Phased Build Plan

## Phase 1 — Core MVP

Build:

* protocol layer
* handshake
* status
* login
* play relay
* static backend configuration
* `/server`
* modern forwarding
* legacy forwarding

Do **not** build yet:

* dashboard
* plugins
* clustering

---

## Phase 2 — Dashboard v1

Build:

* Javalin API
* React dashboard
* overview
* servers
* players
* live WebSocket data
* local authentication

---

## Phase 3 — Plugin Platform

Build:

* `@Plugin`
* Guice dependency injection
* event bus
* plugin classloaders
* command API
* player API
* server API

Build at least one first-party plugin to validate the API.

---

## Phase 4 — Network Intelligence

Introduce the abstractions needed for later clustering:

* backend pools
* load balancing
* health checks
* failover
* draining
* weighted routing
* routing API

At this stage Relay remains a **single proxy**, but that proxy can intelligently
manage large groups of backend servers.

---

## Phase 5 — Hardening

Add:

* RBAC
* Pelican integration
* Prometheus metrics
* configuration editor
* audit log
* security hardening
* load testing
* packet fuzzing

---

## Phase 6 — Multi-Proxy Relay

Introduce:

* Relay node identity
* Relay-to-Relay protocol
* proxy chaining
* proxy pools
* node authentication
* cluster health
* routing metadata
* hop limits
* routing-loop prevention
* topology management

Example:

```text
Player
   ↓
Relay
   ↓
Relay
   ↓
Backend
```

---

## Phase 7 — Distributed Control Plane

Introduce:

* centralized topology
* node registry
* service discovery
* distributed configuration
* network-wide player lookup
* proxy draining
* network-wide metrics
* topology dashboard

At this point Relay begins becoming a true distributed Minecraft network
platform.

---

## Phase 8 — Regional / Large-Network Features

Potential features:

* regional proxy pools
* latency-aware routing
* multi-region failover
* distributed tracing
* rolling proxy updates
* capacity-aware routing
* infrastructure autoscaling integrations

---

# 15. Large-Network Design Principles

Relay should follow several rules if it is intended to eventually support
very large networks.

### No Single Proxy Dependency

Adding capacity should mean:

```text
+ Relay node
```

not:

```text
buy a larger machine
```

### Failure Isolation

Failure of one service group should not bring down unrelated services.

Example:

```text
SkyBlock routing failure
```

should not necessarily affect:

```text
Lobby
BedWars
Creative
```

### Graceful Degradation

Loss of the dashboard/control plane should not immediately disconnect players.

### Local Routing Cache

Relay nodes should cache enough routing state to continue functioning during
temporary controller outages.

### Stateless Where Practical

Avoid unnecessary node-local state that prevents traffic from moving between
Relay nodes.

### Explicit Node Health

Every server and proxy should have a clearly defined health state.

### No Silent Routing Loops

Every multi-hop connection must have route tracing and a hop limit.

### Easy Horizontal Scaling

Adding another proxy to a pool should require minimal network reconfiguration.

---

# 16. Example Large-Network Flow

A player's full connection may eventually look like:

```text
Player
  │
  ▼
External DNS / L4 Load Balancer
  │
  ▼
edge-us-east-02
  │
  │ Select service: SkyBlock
  ▼
skyblock-proxy-pool
  │
  │ Least connections
  ▼
skyblock-proxy-04
  │
  │ Select backend
  ▼
skyblock-backend-pool
  │
  │ Least players
  ▼
skyblock-27
```

Dashboard representation:

```text
Carson
─────────────────────────────────────
UUID            xxxxxxxx-xxxx-...
Region          US-East
Connected       34m
Ping            42 ms

ROUTE

edge-us-east-02
        ↓
skyblock-proxy-04
        ↓
skyblock-27
```

---

# 17. Potential Core Concepts

Relay's long-term API and architecture should revolve around a small number of
clear concepts.

```text
Node
Proxy
Backend
Pool
Route
Player
Region
Health
Capability
```

A possible hierarchy:

```text
Network
│
├── Regions
│   ├── US-East
│   ├── US-West
│   └── Europe
│
├── Proxy Pools
│   ├── Edge
│   ├── Lobby
│   ├── Survival
│   └── Minigames
│
└── Backend Pools
    ├── Lobby
    ├── Survival
    ├── SkyBlock
    └── BedWars
```

Keeping these concepts clean will make both the dashboard and plugin API much
easier to understand.

---

# 18. Risks to Flag Now

## Protocol Version Scope

Supporting many Minecraft protocol versions is an enormous maintenance
burden.

Keep launch support narrow.

---

## Plugin Trust Model

Plugins execute in Relay's JVM.

Document clearly that untrusted plugins should not be installed.

---

## Geyser Interoperability

Confirm forwarding behavior through Geyser rather than assuming Java client
behavior maps perfectly onto Bedrock connections.

---

## Console Streaming at Scale

Streaming every backend log through Relay may become expensive.

Fine for small deployments; larger deployments may eventually require a
dedicated logging system.

---

## TOML+ Java Gap

No Java TOML+ parser currently exists in the project.

Either:

1. implement one later, or
2. retain plain TOML.

Do not block proxy development on it.

---

## Distributed State Complexity

Moving from:

```text
1 Relay
```

to:

```text
50 Relay nodes
```

is not simply a networking problem.

It introduces:

* synchronization
* consistency
* node discovery
* authentication
* leader/controller availability
* stale configuration
* partial failures
* network partitions

Keep this out of v1.

---

## Proxy Chaining Complexity

Every additional hop introduces:

* latency
* another failure point
* more complicated debugging
* metadata forwarding requirements
* routing-loop risks

Relay should only introduce another proxy hop where there is an architectural
reason for it.

---

## Control Plane Dependency

The controller must never become a mandatory per-packet dependency.

Bad:

```text
Packet
  ↓
Ask controller what to do
  ↓
Forward packet
```

Good:

```text
Controller
   ↓
publishes routing state
   ↓
Relay caches state locally

Player packets
   ↓
Relay makes local routing decisions
```

This separation will be critical for high-scale operation.

---

# 19. Long-Term Product Direction

Relay begins as:

> A modern Minecraft proxy with a built-in web dashboard.

It grows into:

> A distributed Minecraft networking platform for routing, load balancing,
> monitoring, and managing server networks.

The desired progression is:

```text
Single Proxy
     ↓
Smart Proxy
     ↓
Backend Pools
     ↓
Load Balancing
     ↓
Proxy Pools
     ↓
Proxy-to-Proxy Routing
     ↓
Distributed Control Plane
     ↓
Regional Network Platform
```

The architecture should allow a small server owner to begin with:

```text
Players → Relay → Paper
```

while allowing a much larger network to eventually grow into:

```text
Players
   ↓
Edge Relay Pool
   ↓
Service Relay Pools
   ↓
Backend Pools
   ↓
Hundreds of Minecraft Servers
```

without having to replace Relay with an entirely different platform.
