# Backend API

Server → proxy communication over plugin messages, the way BungeeCord has always done it.

A plugin on a backend sends a plugin message through one of its own players; Relay
intercepts it on the way past rather than letting it reach the client, acts on it, and
replies the same way in reverse.

**Wire-compatible with BungeeCord.** A plugin written against BungeeCord or Velocity works
against Relay unchanged — same channel, same sub-channel names, same encoding. That is the
point: your existing network plugins keep working, and anything written for Relay stays
portable.

## Channels

On the wire, Relay accepts `bungeecord:main`, the pre-1.13 name `BungeeCord`, and its own
`relay:main`.

**From a Bukkit plugin, use the string `"BungeeCord"`.** Not `bungeecord:main` — that
looks more modern and silently does not work. Bukkit rewrites `bungeecord:main` to
`BungeeCord` when recording what a connection has registered, but does *not* rewrite the
argument to `sendPluginMessage` before checking that set, so the lookup misses and the
message is dropped with no error anywhere. Every BungeeCord plugin uses the legacy string
for this reason. It still travels as `bungeecord:main` on the wire; Bukkit converts it
back on the way out.

`relay:main` is unaffected and can be used either way.

Enabled by `backend-api = true` in `relay.toml` (the default).

## Trust model

**Backends are trusted; clients are not.** These requests are *not* permission-checked —
a backend asking to move a player simply moves them. That is deliberate and matches
BungeeCord: a backend already holds the forwarding secret and sits inside your network, so
it is infrastructure rather than an untrusted peer.

Two consequences worth being deliberate about:

- **Backend ports must be unreachable from outside.** Anyone who can open a socket to a
  backend can, with forwarding misconfigured, reach this API.
- **API traffic is consumed, never relayed to the player.** A client never sees proxy
  control traffic, and cannot inject it either — the interception only reads the backend
  direction.

Contrast the [client API](client-api.md), where every request *is* permission-checked
because a modded client is attacker-controlled.

## Encoding

Java's `DataOutputStream` format — `writeUTF` strings and big-endian primitives — **not**
Minecraft's VarInt encoding. This is BungeeCord's choice and matching it is what makes
existing plugins work.

Every message starts with a sub-channel name as a UTF string.

## Sub-channels

### Requests that act

| Sub-channel | Fields | Effect |
|---|---|---|
| `Connect` | server | Moves the sending player |
| `ConnectOther` | player, server | Moves a named player |
| `Message` / `MessageRaw` | player \| `ALL`, json | Sends chat |
| `KickPlayer` | player, reason json | Disconnects a player |

### Requests that reply

The reply arrives asynchronously on the same channel, beginning with the sub-channel name.

| Sub-channel | Fields | Reply |
|---|---|---|
| `GetServer` | — | server name |
| `GetServers` | — | comma-separated names |
| `PlayerCount` | server \| `ALL` | server, count *(int)* |
| `PlayerList` | server \| `ALL` | server, comma-separated names |
| `IP` | — | address, port *(int)* |
| `UUID` | — | undashed uuid |
| `UUIDOther` | player | player, undashed uuid |

### Cross-server messaging

| Sub-channel | Fields |
|---|---|
| `Forward` | server \| `ALL`, subchannel, length *(short)*, payload |
| `ForwardToPlayer` | player, subchannel, length *(short)*, payload |

The receiving server gets a plugin message on the same channel whose first field is your
chosen sub-channel name. This only reaches servers that currently have a player on them —
a plugin message needs a player connection to travel on. BungeeCord has the same
limitation; Relay logs at debug when a forward had nowhere to go, which is worth knowing
because it otherwise looks like a dropped message.

## From a Paper plugin

The raw form, which is what an existing BungeeCord plugin already does:

```java
ByteArrayOutputStream bytes = new ByteArrayOutputStream();
DataOutputStream out = new DataOutputStream(bytes);
out.writeUTF("Connect");
out.writeUTF("survival");
player.sendPluginMessage(this, "BungeeCord", bytes.toByteArray());
```

Register the channels in `onEnable`:

```java
getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
getServer().getMessenger().registerIncomingPluginChannel(this, "BungeeCord", listener);
```

`RelayApi`, shipped in the Relay plugin, wraps the common operations if you would rather
not hand-roll the streams:

```java
RelayApi relay = new RelayApi(this);
relay.connect(player, "survival");
relay.requestPlayerCount(player, "ALL");
relay.forward(player, "ALL", "MyPluginChannel", payload);
```

## Implementation note

Relay recognises these messages by **peeking at raw frames**, not by registering a packet
decoder. Clientbound play packets are otherwise never parsed, and registering one would
mean a wrong packet id makes Relay misread ordinary backend traffic and kill the
connection — the exact failure mode that cost days of debugging once already. Peeking
degrades instead: anything that does not parse cleanly as a plugin message on an API
channel is forwarded untouched, so the worst case is that this feature quietly does
nothing rather than breaking a session.
