# BlockMirror

Place a block on one server and it appears on another. Break it and it breaks there too.

A worked example of [Relay's packet API](../../docs/server-packets.md), and the smallest
one that is a real thing rather than a ping: two servers, a shared registry of one packet,
and a change on one becoming the same change on the other.

## Running it

Build and copy the jar to both servers:

```bash
./gradlew :examples:block-mirror:build
```

It needs the Relay plugin beside it — that is where `PacketFactory` lives, and `plugin.yml`
makes it a hard dependency so a missing Relay is a clear refusal to load rather than a
`NoClassDefFoundError` on the first block somebody places.

Then point each server at the other, in `plugins/BlockMirror/config.yml`:

```yaml
# on lobby
mirror-to: "survival-00"
```
```yaml
# on survival-00
mirror-to: "lobby"
```

A backend name or a group name, as the proxy knows it — the same thing you would type after
`/server`. Two servers pointing at each other give a two-way mirror; several pointing at one
name give a broadcast. Blank, the default, does nothing: installing this by accident should
not start editing somebody's world.

Both servers log a registry fingerprint on startup. **They must print the same number.**

```
[BlockMirror] Mirroring block changes to 'survival-00' (registry 1402588393).
```

## Proving it without two people

```
/mirrortest 100 70 100 minecraft:diamond_block
```

Runnable from the console, so it works over RCON. It takes the same path a placed block
does — encode, forward, proxy, decode, main thread, set — and skips only the two event
handlers.

## The whole thing

Three files, and the interesting part is about forty lines:

```java
packets = PacketFactory.named("block-mirror")
        .register(BlockChange.class, BlockChange::new)
        .build(this);
packets.on(BlockChange.class, this::apply);
```

```java
@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
public void onPlace(BlockPlaceEvent event) {
    Block block = event.getBlockPlaced();
    send(block, block.getBlockData().getAsString(), event.getPlayer().getName());
}
```

Four decisions in there are worth more than the line count suggests.

**MONITOR priority, ignoring cancelled.** At any earlier priority this would mirror
placements a protection plugin then refused, and the two worlds would disagree from the
first build somebody was not allowed to make.

**The block travels as a string.** `minecraft:oak_stairs[facing=east,half=bottom,...]` is
Bukkit's own round-trippable form and the only one that survives reaching a server on a
different build. Block *ids* are internal and change between versions; using one would
silently mirror a staircase as something else the first time the two servers were not
identical.

**A break is a place of air.** One packet covers both directions — at the point the far
side applies it there is no difference worth two classes, and two would mean the same three
lines in two handlers and two chances to fix a bug in only one.

**The applied change hops to the main thread.** Whether a plugin message arrives there is a
detail of the server implementation rather than a promise, and setting a block from the
wrong thread is the kind of bug that works in testing and corrupts a chunk on a busy server.

## The loop that is not there

The obvious fear is a place that mirrors, which mirrors back, forever.

It does not, because `BlockPlaceEvent` and `BlockBreakEvent` are *player* events: setting a
block through the API fires neither. The applied change is silent by construction rather
than by a flag that has to be got right — worth knowing before extending this to react to
block changes from other sources, where it would no longer hold.

## What this is not

Not a world-sync plugin. It mirrors the two events it listens to and nothing else: no
pistons, no water, no TNT, no fire, no chunks that were already different when you started,
and no reconciliation when the two worlds drift. Nor does it care who is where — a block
placed on lobby appears on survival-00 whether or not anybody is standing there to see it.

It is a demonstration that a typed packet crosses the proxy and does something visible on
the other side.
