# Client API

A protocol for a client-side mod to talk to Relay directly, over the plugin-message
channel `relay:api_v1`.

This is the piece Velocity and BungeeCord leave to you. They give plugins a raw
plugin-messaging pipe; a client mod author has to invent a protocol, a versioning scheme
and a permission story from scratch, per network. Relay makes it a first-class surface
with those three problems already solved.

## Design properties

**Invisible to vanilla.** Relay never sends on this channel unsolicited. A vanilla client
never sends on it either, so an unmodded player is completely unaffected. Everything
starts with the client saying hello.

**Version negotiated.** Both sides state a protocol version and the lower is used, so a
mod and a proxy can be upgraded independently. Old mods keep working against new proxies.

**Never trusted.** This is the important one. A modded client is an attacker-controlled
peer — anyone can write a mod that claims anything. The welcome message tells a client
which capabilities it *may* use so a mod can grey out buttons, but that list is a
convenience for rendering, never the security boundary. Every request is re-checked
against the same permission nodes that guard the in-game commands, at the moment it is
acted on. A modded client can do nothing a player could not do by typing `/server`.

**Proxy-owned.** The channel is claimed by Relay and never relayed to a backend. Backends
have no idea what these messages mean, and forwarding them would let a client reach
backend plugins over a channel they never opted into.

## Limits

| | |
|---|---|
| Channel | `relay:api_v1` |
| Protocol version | 1 |
| Max message size | 32 KB (larger is refused unread) |
| Rate limit | 20 messages/second, then `RATE_LIMITED` |
| Config | `client-api = true` in `relay.toml` |

Malformed input is answered with an error and the session continues. Only two things drop
the connection: a message over the size cap, and input that cannot be framed at all.

## Wire format

All messages are a VarInt type followed by a body. Strings are Minecraft's standard
VarInt-length-prefixed UTF-8.

### Serverbound (client → proxy)

**`0x00` Hello** — must be first; anything before it is refused.

| Field | Type |
|---|---|
| Client protocol version | VarInt |
| Mod name | String (≤64, logging only) |

**`0x01` List Servers** — no body. Requires `relay.command.glist`.

**`0x02` Request Switch**

| Field | Type |
|---|---|
| Server name | String (≤64) |

Requires `relay.command.server`.

### Clientbound (proxy → client)

**`0x00` Welcome**

| Field | Type |
|---|---|
| Negotiated version | VarInt |
| Proxy identity | String |
| Capability count | VarInt |
| Capabilities | String × count |

Capabilities are `server_list` and `switch`, filtered to what this player may actually do.

**`0x01` Server List**

| Field | Type |
|---|---|
| Count | VarInt |
| — name | String |
| — player count | VarInt |
| — is current | Boolean |

**`0x02` Switch Status**

| Field | Type |
|---|---|
| Server name | String |
| Status | VarInt — 0 connecting, 1 connected, 2 failed |
| Detail | String (failure reason, else empty) |

**`0x03` Error**

| Field | Type |
|---|---|
| Code | VarInt |

Codes: `0` no permission, `1` unknown server, `2` switch in progress, `3` already
connected, `4` rate limited, `5` malformed.

## Example exchange

```
client → 0x00 Hello        { version: 1, mod: "RelayClient" }
proxy  → 0x00 Welcome      { version: 1, "Relay 0.1.0", ["server_list", "switch"] }
client → 0x01 ListServers  { }
proxy  → 0x01 ServerList   { lobby: 12 (current), survival: 47, creative: 3 }
client → 0x02 RequestSwitch{ "survival" }
proxy  → 0x02 SwitchStatus { "survival", connecting }
proxy  → 0x02 SwitchStatus { "survival", connected }
```

## What this makes possible

The two capabilities implemented are the foundation. What they enable, and what could be
built on the same channel:

- **A real server browser.** A GUI with live player counts and current-server highlight,
  instead of `/server` chat spam. Already possible with what is here.
- **Switch progress.** The client shows "Connecting to survival…" and a real outcome
  instead of a frozen screen. Already possible with what is here.
- **Queue position in the HUD.** A number that updates in place, rather than a chat
  message every five seconds. Needs a queue capability plus a queue system.
- **Cross-server presence.** A friends list showing which backend people are on. Relay
  already knows this — `PlayerRegistry` has it — it needs a capability and a privacy
  decision about who may see whom.
- **Per-backend latency.** Ping measured *from the proxy*, which is the number that
  actually predicts play quality, shown in the browser.
- **Transfer-surviving client state.** A mod keeping its own state across a switch,
  driven by the switch status messages.

## Building the mod

The mod itself is a separate project — it needs a Fabric or NeoForge toolchain and cannot
live in this repository. What it needs to do:

1. Register `relay:api_v1` as a receiving channel.
2. Send Hello on join. Configuration state works too, but play state is simpler.
3. If a Welcome arrives, the player is on a Relay proxy — enable the UI. If nothing
   arrives, they are not; stay silent.
4. Render only the advertised capabilities.
5. Expect an error for anything, including actions that were advertised. Permissions can
   change mid-session.

Point 3 is the graceful-degradation story: the same mod works on any server, and simply
does nothing where the proxy is not Relay.

## Versioning

The channel name carries `v1`. A backwards-compatible change (new message types, new
capabilities) bumps `PROTOCOL_VERSION` and keeps the channel. A breaking change gets a new
channel name, so an old mod and a new proxy talk past each other harmlessly instead of
misinterpreting each other's bytes.
