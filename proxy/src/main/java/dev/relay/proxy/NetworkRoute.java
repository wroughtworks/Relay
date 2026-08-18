package dev.relay.proxy;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * The path a player's traffic actually takes, hop by hop.
 *
 * <p>Spec &sect;9.1 asks the Players view for a "complete route", and gives the shape it
 * should eventually take:
 *
 * <pre>
 * edge-us-east-01
 *     &darr;
 * survival-proxy-03
 *     &darr;
 * survival-07
 * </pre>
 *
 * <p>Most of that belongs to the multi-proxy topology of &sect;7, which is a v2 concern.
 * What is built here is the part that is true today and answers real questions:
 *
 * <ul>
 *   <li><b>The hostname they connected to</b>, which is not decoration. With forced hosts,
 *       it is the reason they landed where they did, and "why did this player end up on
 *       survival" is otherwise unanswerable from the outside.</li>
 *   <li><b>An upstream hop</b>, when a PROXY protocol header says one exists. Relay
 *       already reads that header to recover the player's real address; the fact that
 *       something sat in front is worth showing rather than throwing away.</li>
 *   <li><b>This node</b>, by the name from &sect;7.9, so a route read on one proxy still
 *       makes sense when there are several.</li>
 *   <li><b>The group</b>, when the backend is in one, because {@code survival} deciding
 *       and {@code survival-02} answering are two different facts.</li>
 *   <li><b>The backend</b> they are on.</li>
 * </ul>
 *
 * <p>The {@code routeId} of &sect;7.7 is issued now even though loop prevention is a v2
 * problem, because it is independently the cheapest thing that makes a session greppable:
 * one short token tying together every log line, event and dashboard row for one player's
 * visit.
 */
public record NetworkRoute(String routeId, String virtualHost, List<Hop> hops) {

    /**
     * One step along the way.
     *
     * @param kind   what this hop is, so a display can treat an edge differently from a
     *               backend without parsing names
     * @param name   the hop's identity
     * @param detail an address, a member count, or whatever makes the hop legible; may be
     *               null
     */
    public record Hop(Kind kind, String name, String detail) {
    }

    public enum Kind {
        /** Something in front of Relay, known only because it sent a PROXY header. */
        EDGE,
        /** A Relay node. Exactly one today; several once §7 chaining exists. */
        PROXY,
        /** The group that chose, when the destination was a group rather than a server. */
        POOL,
        /** The backend the player is actually on. */
        BACKEND
    }

    /** How many hops the traffic makes, which is {@code hopCount} in §7.7's terms. */
    public int hopCount() {
        return hops.size();
    }

    /**
     * Builds the route for a player as things currently stand.
     *
     * <p>Recomputed on demand rather than stored, because most of it changes: a switch
     * replaces the last two hops, and caching it would mean a dashboard showing where
     * someone used to be. Only the {@code routeId} is fixed for the session.
     */
    public static NetworkRoute of(ConnectedPlayer player, RelayProxy proxy) {
        List<Hop> hops = new ArrayList<>(4);

        // An upstream only shows up when it announced itself with a PROXY header, which
        // is the only way Relay can know it is there at all.
        InetSocketAddress declared = player.proxiedFrom();
        if (declared != null) {
            hops.add(new Hop(Kind.EDGE, declared.getHostString(),
                    "upstream proxy, port " + declared.getPort()));
        }

        hops.add(new Hop(Kind.PROXY, proxy.config().nodeName(),
                proxy.config().bind().getHostString() + ":" + proxy.config().bind().getPort()));

        ServerConnection current = player.connectedServer();
        if (current != null) {
            RegisteredServer backend = current.target();
            for (ServerGroup group : proxy.groups()) {
                if (group.members().contains(backend)) {
                    hops.add(new Hop(Kind.POOL, group.name(),
                            group.members().size() + " members, "
                                    + proxy.config().balance().configName()));
                    break;
                }
            }
            hops.add(new Hop(Kind.BACKEND, backend.name(),
                    backend.address().getHostString() + ":" + backend.address().getPort()));
        }

        return new NetworkRoute(player.routeId(), player.virtualHost(), List.copyOf(hops));
    }
}
