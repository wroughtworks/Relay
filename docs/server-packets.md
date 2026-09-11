# Typed packets between servers

`Forward` (see [backend-api.md](backend-api.md)) has always been able to carry bytes from
one backend to another through the proxy: a server name and an opaque payload. What it
leaves every plugin to invent is the layer above — deciding what the bytes mean, keeping
the two ends agreeing on it, and getting a message to the code that handles it.

`PacketFactory`, in the Relay plugin, is that layer.

```java
PacketFactory packets = PacketFactory.named("parties")
        .register(PartyInvite.class, PartyInvite::new)
        .register(PartyDisband.class, PartyDisband::new)
        .build(this);

packets.on(PartyInvite.class, (from, invite) ->
        getLogger().info(invite.to + " was invited, from " + from));

packets.send("survival", new PartyInvite(player.getUniqueId(), "Notch", 120));
```

Both servers build the factory from **the same code** — normally a shared module both
plugins depend on, or the same plugin jar deployed to both.

## A packet

```java
public final class PartyInvite implements RelayPacket {
    public UUID from;
    public String to;
    public int expiresInSeconds;

    public PartyInvite() {}                    // the receiving side reads into a blank one

    public PartyInvite(UUID from, String to, int expiresInSeconds) { ... }

    @Override public void write(PacketBuffer out) {
        out.writeUuid(from);
        out.writeString(to);
        out.writeVarInt(expiresInSeconds);
    }

    @Override public void read(PacketBuffer in) {
        from = in.readUuid();
        to = in.readString();
        expiresInSeconds = in.readVarInt();
    }
}
```

`write` and `read` must agree, field for field, in order. Nothing enforces it, and a
mismatch does not fail where the mistake is — it fails at whatever field happens to be
next, on the other server. The factory does notice when a read stops short of the bytes
that arrived and warns about it, which catches the common half-finished version bump
before it starts costing data.

`PacketBuffer` has `writeString`/`readString` (UTF-8, length-prefixed, no 64KB ceiling),
`writeVarInt`, `writeUuid`, `writeEnum` (by constant name, so reordering an enum does not
reroute data), `writeBytes`, `writeStrings`, and the primitives.

## Ids, and the thing that makes them safe

**A packet's id is its position in the registration list.** That is what lets both ends
agree without anyone writing a number down.

It is also the obvious way to get this catastrophically wrong. Two servers that register
in different orders each decode the other's packets into the wrong class and read one
field's bytes as another's — a party invite arriving as a shop sync, with
plausible-looking garbage in it, and nothing anywhere saying so.

So every message carries a **fingerprint** of the registry that produced it: the factory
name and every registered class name, in order. A packet whose fingerprint does not match
the receiver's is dropped and reported, naming what this side has registered:

```
[Parties] Ignoring a 'parties' packet from a registry fingerprinted -1580312955; this one
is 884471301. The two servers have registered different packets, or registered them in a
different order, and decoding it would read one packet as another. Here it is
0=PartyInvite, 1=PartyDisband.
```

Reported once per mismatching registry, not once per packet. `packets.fingerprint()` is
worth logging on startup: two servers printing different numbers is the whole explanation
for messages that vanish.

**Adding a packet is safe; inserting one is not.** Register new types at the end and a
server a version behind still agrees about the old ones — until you deploy, when the
fingerprints differ and everything stops rather than being misread. Deploy both sides.

## Addressing

| | |
|---|---|
| `send("survival-02", packet)` | one backend |
| `send("survival", packet)` | every member of a group — which member a player is on is the proxy's business |
| `sendToPlayer("Notch", packet)` | whichever server that player is on |
| `broadcast(packet)` | every server with somebody on it, this one excepted |

A group that contains **this** server includes it: the packet goes round and arrives back
here too. That is usually what "tell the survival pool" should mean, but write the handler
knowing it.

### What the return value means

`false` is "this server could not send it" — nobody is online here to carry it, the packet
would not encode, or it was over the size limit.

**`true` is not a delivery receipt.** The proxy decides where the packet goes after the
call returns, and a name matching no backend — or one with nobody on it — is dropped there
with nothing to report back. The proxy logs that at debug; the sender sees a clean `true`.
Plugin messaging has no acknowledgement to build one out of, so a protocol that needs to
know its message arrived has to say so with a packet of its own. The `Ping`/`Pong` pair in
`PacketProbe` is the smallest example of doing that.

## The one real limitation

A plugin message travels on a player's connection. **A server with nobody on it cannot be
reached, and cannot send.** This is inherited from how plugin messaging works and
BungeeCord has it too.

The two directions are handled differently because they mean different things:

- Sending **to** an empty server is a silent drop, at the proxy, after the call has already
  returned `true` — see above. The proxy logs it at debug.
- Sending **from** a server that has not yet learned its own name holds the packet, up to
  64 of them, and sends them when it does. That state is almost always "nobody has joined
  yet", because this server learns its name by asking the proxy through a player — the
  same player any packet would have travelled on. A plugin sending during startup is early
  rather than wrong. Past 64 held, the oldest are refused with a warning rather than
  accumulating on a server that may never get a player.

A packet may be at most 32KB encoded, matching the proxy's forwarding limit. Larger is
refused on the sending side, where the log names the packet: the length travels as an
unsigned short, so an oversized one would not bounce, it would be read with a wrapped
length and take the rest of the message stream with it.

## What it costs

Nothing on the proxy. `PacketFactory` is built entirely on `Forward` and `ForwardToPlayer`
as they already exist, so a network running this needs no Relay version in particular and
the proxy never parses a byte of your payload. Packets travel under the sub-channel
`RelayPacket:<factory name>`, which is what keeps two plugins on one server from reading
each other's mail.

## Checking it on a live network

The Relay plugin registers a factory of its own, so a network can prove the whole path
without writing anything:

```
/relay packet survival-00 hello
```

Also runnable from the console or over RCON, because a factory picks its own carrier and
does not need the sender to be a player. The sending server logs the registry fingerprint
and the round trip; the receiving server logs the ping as it arrives:

```
[lobby]       Packet probe: pinging 'survival-00' (registry -1564738630, sub-channel RelayPacket:relay-probe)
[survival-00] Packet probe: ping from lobby (hello); replying
[lobby]       Packet probe: survival-00 answered in 10ms (hello)
```

`PacketProbe` in the plugin source is about twenty lines and is the worked example: it is
everything a plugin has to do to use this.

## A worked example

[`examples/block-mirror`](../examples/block-mirror) is the smallest useful thing built on
this: place a block on one server and it appears on the other, break it and it breaks
there too. About forty interesting lines, one packet, and a README covering the four
decisions in it that matter more than the line count suggests.

## Client mods

This is server ↔ server. Reaching a **modded client** is the [client
API](client-api.md), which is a different trust model — a client is attacker-controlled,
so every request there is permission-checked, where backends are trusted infrastructure.
A factory whose packets could arrive from a player's computer would quietly hand that
trust to whoever wrote the mod.
