# Protocol IDs

Relay decodes about twenty packets out of the several hundred Minecraft defines.
Everything else is relayed as an opaque frame. This document lists the ids Relay
actually depends on, so adding a Minecraft version means checking this page rather than
auditing a protocol dump.

**Seven of the nine supported versions are now verified against first-party data**, and
two are not. Protocol 767–772 come from Mojang's own generated packet report (see [Where
these numbers come from](#where-these-numbers-come-from)); 764 was confirmed against a
live Paper 1.20.2 instance, either its own packet log (`net.minecraft.network` at DEBUG
prints every id it handles) or Relay's packet trail. Rows marked ✓ are that live check;
`†` marks the two versions with neither.

This page previously said the rest was inference and should be verified. When it finally
was, **26 of 72 checked ids were wrong** — see the note at the end of the Play section.
Do not extend a row by pattern; generate the report.

## The bug this table caused

A frame's *length* prefix, not its id, once closed connections at random. The framing
decoder treated a leading `0xFE` as a pre-1.7 legacy ping and closed the channel — but
`0xFE` is also the first byte of the length VarInt for any frame of 254, 382, 510 … bytes,
so roughly one frame in 128 was mistaken for a ping. A busy connection hit one within a
second, with no exception and no disconnect packet, leaving each end convinced the other
had hung up. The check now runs only on the first byte of a player connection.
`PipelineCodecTest` guards it.

The source of truth is [`StateRegistry.java`](../proxy/src/main/java/dev/relay/protocol/StateRegistry.java).

## Why a wrong id degrades instead of corrupting

The registrations are arranged so that a mistake here has a bounded, visible blast
radius:

| Area | If the id is wrong | Severity |
|---|---|---|
| Clientbound **play** | Registered write-only. Relay never decodes backend→client play traffic, so gameplay is unaffected. Only Relay's own messages and the switch prompt break. | Contained |
| Serverbound **play** | Three packets are decoded: the unsigned chat command, the configuration acknowledgement, and the client-API plugin message. A wrong chat id makes `/server` fall through to the backend as unknown; a wrong configuration id breaks switching. | Mixed |
| **Configuration** | A wrong id breaks joining. Loud and immediate. | Blocking |
| Handshake / status / **login** | Stable across the whole supported range. | Very unlikely to move |

The asymmetry is deliberate. The direction carrying 99% of the bytes — backend to client
during play — has no id dependency at all.

## Fixing an id without a rebuild

Add an override to `relay.toml`. It is applied at startup, before the listener opens.

```toml
[protocol.overrides]
"play.clientbound.start_configuration.773" = 0x70
"play.serverbound.chat_command.773" = 0x07
```

A hypothetical future version, deliberately — the ids in this file are now generated
rather than guessed, so an override is for the gap between a Minecraft release and a Relay
one, not for patching around a suspect table.

The key is `state.direction.packet.protocolVersion`. Relay logs a warning for each
override in effect, so an emergency fix does not quietly become permanent.

## Handshake

| Direction | Packet | ID | Versions |
|---|---|---|---|
| serverbound | `handshake` | `0x00` | all |

## Status

| Direction | Packet | ID | Versions |
|---|---|---|---|
| serverbound | `status_request` | `0x00` | all |
| serverbound | `status_ping` | `0x01` | all |
| clientbound | `status_response` | `0x00` | all |
| clientbound | `status_pong` | `0x01` | all |

## Login

Stable across the supported range. 1.20.5 added cookie packets *after* the ids Relay
uses, so nothing shifted.

| Direction | Packet | ID | Versions |
|---|---|---|---|
| serverbound | `login_start` | `0x00` ✓ | all |
| serverbound | `encryption_response` | `0x01` | all |
| serverbound | `login_plugin_response` | `0x02` ✓ | all |
| serverbound | `login_acknowledged` | `0x03` ✓ | 1.20.2+ |
| clientbound | `login_disconnect` | `0x00` | all |
| clientbound | `encryption_request` | `0x01` | all |
| clientbound | `login_success` | `0x02` ✓ | all |
| clientbound | `set_compression` | `0x03` ✓ | all |
| clientbound | `login_plugin_request` | `0x04` ✓ | all |

Field-level version differences, handled in the packet classes rather than here:

- `encryption_request` gained a trailing `shouldAuthenticate` boolean in **1.20.5**.
- `login_success` gained `strictErrorHandling` in **1.20.5** and lost it again in
  **1.21.2**. It is bracketed, not gated on a lower bound.
- `login_start` carries an unconditional UUID from 1.20.2, which is Relay's floor.

## Configuration

1.20.5 inserted cookie packets at id `0x00` in both directions, shifting everything after
by one.

| Direction | Packet | 1.20.2 – 1.20.4 | 1.20.5+ |
|---|---|---|---|
| serverbound | `plugin_message` | `0x01` ✓ | `0x02` |
| serverbound | `finish_configuration_ack` | `0x02` ✓ | `0x03` |
| clientbound | `plugin_message` | `0x00` ✓ | `0x01` |
| clientbound | `disconnect` *(write-only)* | `0x01` | `0x02` |
| clientbound | `finish_configuration` | `0x02` ✓ | `0x03` |

## Play

**The volatile section.** These shift whenever Mojang inserts a packet mid-list.

Values below are per protocol version. `†` marks the two versions with no packet report,
where the id is bracketed by verified neighbours rather than proven.

### Serverbound (decoded)

| Packet | 764 | 765† | 766† | 767 | 768 | 769 | 770 | 771 | 772 |
|---|---|---|---|---|---|---|---|---|---|
| `chat_command` | `0x04` ✓ | `0x04` | `0x04` | `0x04` | `0x05` | `0x05` | `0x05` | `0x06` | `0x06` |
| `configuration_acknowledged` | `0x0B` ✓ | `0x0B` | `0x0C` | `0x0C` | `0x0E` | `0x0E` | `0x0E` | `0x0F` | `0x0F` |
| `plugin_message` | `0x0F` ✓ | `0x0F` | `0x12` | `0x12` | `0x14` | `0x14` | `0x14` | `0x15` | `0x15` |

Notes on what moved and why:

- **1.20.5** split the command packet into unsigned *Chat Command* and *Signed Chat
  Command*. Relay registers only the unsigned form; signed commands pass through
  untouched, since rewriting one would invalidate its signature. On 1.20.2/1.20.3 there
  is a single signed packet at `0x04`, and Relay reads only its leading command string,
  keeping the remainder as opaque bytes.
- `plugin_message` does **not** track `configuration_acknowledged`'s shifts, though this
  page claimed for months that it did. It is four past it at 1.20.2 and six past it from
  1.21 — packets were inserted between the two. That assumption made this row wrong at
  every version above 1.20.4.

### Clientbound (write-only — never decoded)

| Packet | 764 | 765† | 766† | 767 | 768 | 769 | 770 | 771 | 772 |
|---|---|---|---|---|---|---|---|---|---|
| `disconnect` | `0x1B` ✓ | `0x1B` | `0x1D` | `0x1D` | `0x1D` | `0x1D` | `0x1C` | `0x1C` | `0x1C` |
| `system_chat` | `0x67` ✓ | `0x69` | `0x6C` | `0x6C` | `0x73` | `0x73` | `0x72` | `0x72` | `0x72` |
| `start_configuration` | `0x65` ✓ | `0x67` | `0x69` | `0x69` | `0x70` | `0x70` | `0x6F` | `0x6F` | `0x6F` |
| `plugin_message` | `0x18` | `0x19` | `0x19` | `0x19` | `0x19` | `0x19` | `0x18` | `0x18` | `0x18` |

Note that `system_chat` and `start_configuration` **cross over** at 1.21.5: the "+2 offset"
relationship that holds at 1.20.2 does not survive the range, and assuming it did is what
made both rows wrong at the top end.

## Where these numbers come from

Every id from protocol 767 up is read out of **Mojang's own packet report**, which the
server jar generates on demand:

```
java -DbundlerMainClass=net.minecraft.data.Main -jar <server>.jar --reports
```

That writes `generated/reports/packets.json` — every packet, every state, every direction,
with its id, from the same source that defines them. No client, no network, no community
table, and nothing to drift out of date.

Trimmed copies live in `proxy/src/test/resources/protocol/packets-<protocol>.json`, each
recording the Paper build it came from. `MojangPacketReportTest` holds `StateRegistry`
against them and `FakeClientProtocolTest` holds the fake client's table against them, one
dynamic test per packet per version.

**1.20.2 (764)** predates the report provider and rests on a live Paper debug log instead.
**1.20.3–1.20.6 (765, 766)** have neither: Mojang added the provider in 1.21, and no
1.20.x jar can produce one. Their rows are bracketed by verified neighbours — better than
extrapolation, still not proof, and marked `†` above.

> **This check found 26 wrong ids on the day it was written**, out of 72. Every version
> above 1.20.4 was affected. `start_configuration` and `configuration_acknowledged` were
> both wrong at 1.21.6+, which means server switching could not have worked there at all;
> `chat_command` was wrong at the same versions, so `/server` would never have reached
> Relay to begin with. `plugin_message` was wrong in both directions across most of the
> range, breaking the backend API.
>
> None of it was carelessness — the values were derived by exactly the reasoning this page
> described, and that reasoning was wrong. The lesson is not "be more careful with
> inference"; it is that a generated first-party source existed the whole time.

Every 1.20.2 id above is now confirmed, and the last two were confirmed by a real client
rather than by reading a log. A live 1.20.2 client switched servers with `/server`, which
can only happen if `start_configuration` (`0x65`) is the packet the client accepts *and*
`configuration_acknowledged` (`0x0B`) is the reply Relay recognises. A wrong value for
either leaves the player stuck on the old backend.

`disconnect` was confirmed the same way, and it is the one entry in this table Relay
now *reads* rather than only writes. A backend's kick is claimed instead of relayed, so
that a planned restart moves players rather than ejecting them &mdash; and a live 1.20.2
Paper server typing `stop` produced exactly that, which it could not have done had the id
been wrong. See `BackendKickHandler` for how a wrong id degrades: the frame is peeked at
rather than decoded, rejected unless its body could be a text component, and otherwise
relayed untouched as before.

Two more, from driving a fake client against a live Paper 1.20.2 server with packet
logging on. Relay decodes neither, but anything speaking the protocol by hand needs both:

- **Clientbound keep-alive is `0x24`** (`OUT: [play:36] ClientboundKeepAlivePacket`).
- **Clientbound Synchronize Player Position is `0x3E`** (`OUT: [play:62]
  PacketPlayOutPosition`), answered with serverbound `0x00`, Confirm Teleportation.

The second is the one worth knowing about: **a server does not begin its keep-alive cycle
until the join teleport is confirmed.** A client that never confirms is held unspawned,
receives no keep-alives at all, and is eventually dropped — which looks exactly like a
keep-alive problem and is not one.

And a warning about identifying packets by size. A keep-alive carries a bare long, so an
eight-byte body seems diagnostic; it is not. Entity metadata (`0x54`) and relative entity
movement (`0x2c`) are frequently eight bytes too, and answering one of those as a
keep-alive is worse than ignoring it — a server treats a response it never asked for as a
timeout and kicks immediately.

One more id worth recording even though Relay does not decode it: Paper's log shows
`IN: [play:20] ServerboundKeepAlivePacket`, so **Keep Alive is `0x14`** at 1.20.2, not
`0x12` as several community tables have it. That matters as a check on the ones above:
everything at or below `0x0F` is unshifted relative to those tables, and the drift starts
after it. `chat_command`, `configuration_acknowledged` and `plugin_message` all sit below
the shift, which is why they were right.

> **Two bugs were found here on 2026-07-23, both from a real server log.** `system_chat`
> was `0x64` at 1.20.2 when Paper's own packet log proves it is `0x67`
> (`OUT: [play:103] ClientboundSystemChatPacket`). Worse, `system_chat` and
> `start_configuration` had been given *identical* ids at 1.20.3 and 1.20.5 — impossible,
> and silent, because both are write-only. A player would have received a chat message
> where a state change belonged.
>
> `StateRegistry` now runs `verifyNoDuplicateIds()` at class initialisation, so any two
> packets claiming one id fail at startup rather than in front of a player. The later
> `system_chat` values keep the +2 offset from `start_configuration` that holds at 1.20.2;
> verify them before trusting them.

## The second table: what the fake client speaks

`FakePlayers` carries its **own** id table (`FakePlayers.IDS`), separate from
`StateRegistry`. That is deliberate. A fake client that read its ids out of the proxy it
is testing would agree with that proxy by construction — if Relay's table were wrong for
some version, the tool would speak the same wrong ids and every fake player would connect
happily, proving nothing.

Both tables now answer to the packet report instead of to each other, which is stronger:
agreeing with each other would only prove they were copied from the same place.
`MojangPacketReportTest` checks `StateRegistry`; `FakeClientProtocolTest` checks the fake
client, including five ids Relay has no opinion about at all — it forwards these as opaque
frames and never decodes them, so nothing else in the project would notice them moving:

| Packet | 764 | 767 | 768–769 | 770 | 771–772 | Why the tool needs it |
|---|---|---|---|---|---|---|
| clientbound keep-alive | `0x24` | `0x26` | `0x27` | `0x26` | `0x26` | Answering it is what keeps a fake player alive |
| serverbound keep-alive | `0x14` | `0x18` | `0x1A` | `0x1A` | `0x1B` | The reply |
| clientbound Synchronize Player Position | `0x3E` | `0x40` | `0x42` | `0x41` | `0x41` | The join teleport |
| serverbound Confirm Teleportation | `0x00` | `0x00` | `0x00` | `0x00` | `0x00` | Without it the server never starts sending keep-alives |
| serverbound Client Information (config) | `0x00` | `0x00` | `0x00` | `0x00` | `0x00` | Paper drops a player it knows nothing about |

Every version Relay supports is driveable except **1.20.3–1.20.6**, which have no packet
report. Those are refused with an explanation rather than guessed at:

```
$ py relay.py fake --protocol 765
No verified packet ids for protocol 765 (1.20.3-1.20.4).
```

The ids almost certainly shift by the same amounts as the rest of this page, and filling
them in from that pattern would take a minute. It is not done, because a
plausible-but-wrong id produces a tool that reports a proxy bug which does not exist — the
one failure mode a test tool must not have. An absent version says "unverified" out loud;
a guessed one says nothing at all. The 26 wrong ids this page used to carry are what that
looks like when nobody notices.

### Filling in 1.20.3–1.20.6

These are the only versions left, and they need the older method, since no 1.20.x jar can
generate a report:

1. Run a backend of that version under `py relay.py paper-debug <name>`, which writes
   Paper's `log4j2` config so `net.minecraft.network` logs every packet id it handles.
2. Join with a real client of that version and switch servers with `/server`.
3. Read the ids out of the log: `OUT: [play:N] ClientboundKeepAlivePacket` and friends.
   The bracketed number is decimal.
4. Add the row to `FakePlayers.IDS` and correct `StateRegistry`, then update the `†`
   markers above.

## Adding a Minecraft version

1. Add the constant to `ProtocolVersion` with its protocol number.
2. Download that version's server jar and generate its report:
   `java -DbundlerMainClass=net.minecraft.data.Main -jar <server>.jar --reports`.
3. Trim `generated/reports/packets.json` to the packets Relay uses and save it as
   `proxy/src/test/resources/protocol/packets-<protocol>.json`, recording the build it
   came from in `_source`.
4. Add the protocol number to `VERIFIED` in `MojangPacketReportTest` and run it. It now
   tells you exactly which ids moved, one failure per packet.
5. Add a `map(newId, NEW_VERSION)` entry for each — mappings apply from their version
   until the next one supersedes them. Re-run until green.
6. Add the row to `FakePlayers.IDS` too, so the load tool can drive the new version;
   `FakeClientProtocolTest` checks it against the same report.
7. `StateRegistryTest.everyRegisteredPacketHasAnIdAtEveryVersion` catches anything with no
   mapping at all, and `verifyNoDuplicateIds()` runs at class initialisation.
8. Finally, verify against a real client: join, run `/server`, switch backends, and confirm
   chunks load on the far side. The report proves the ids; only a client proves the proxy.

**Do not skip to step 5 by reading the pattern off the table.** That is how the previous
26 errors got in.

## What Relay deliberately does not decode

Not an oversight — this is the containment strategy:

- Registry data, tags, feature flags, resource packs — forwarded as opaque configuration
  frames.
- Join Game, Respawn, chunk data, entity packets — never parsed. The client goes through
  a full configuration cycle on every switch, so Relay does not need to rewrite dimension
  or entity ids the way pre-1.20.2 proxies did.
- Signed chat and signed commands — forwarded byte for byte.
- Keep-alives — forwarded. Each side answers its own peer's, which is correct: a backend
  stall should surface as a backend timeout.
