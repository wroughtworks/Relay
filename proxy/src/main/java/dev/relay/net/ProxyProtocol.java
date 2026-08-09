package dev.relay.net;

import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * Builds HAProxy PROXY protocol headers.
 *
 * <p>The PROXY header prefixes a TCP connection with the address of the client that
 * originally opened it, so a server behind a proxy can log and act on the real address
 * rather than the proxy's. Relay emits one per backend connection when configured to,
 * which is what a Paper backend running with {@code proxies.proxy-protocol: true}
 * expects. Without it that backend buffers the connection waiting for a header that
 * never arrives, answering nothing at all.
 *
 * <p>Version 2 (binary) is used rather than version 1 (text). Both are accepted by
 * every current implementation, and the binary form is unambiguous about address family
 * instead of relying on parsing a line of text.
 */
public final class ProxyProtocol {

    private ProxyProtocol() {
    }

    /**
     * Describes a connection relayed on behalf of {@code source}.
     *
     * @param source      the player's own address, which is what the backend should see
     * @param destination the backend address the connection was made to
     */
    public static HAProxyMessage header(InetSocketAddress source, InetSocketAddress destination) {
        InetAddress sourceAddress = source.getAddress();
        InetAddress destinationAddress = destination.getAddress();

        // The header must describe both endpoints in one address family. A player on
        // IPv6 reaching an IPv4 backend (or the reverse) is entirely normal on a
        // dual-stack host, so the narrower address is widened rather than the header
        // being skipped.
        boolean ipv6 = sourceAddress instanceof Inet6Address || destinationAddress instanceof Inet6Address;

        return new HAProxyMessage(
                HAProxyProtocolVersion.V2,
                HAProxyCommand.PROXY,
                ipv6 ? HAProxyProxiedProtocol.TCP6 : HAProxyProxiedProtocol.TCP4,
                literal(sourceAddress, ipv6),
                literal(destinationAddress, ipv6),
                source.getPort(),
                destination.getPort());
    }

    /**
     * Renders an address as a literal of the requested family, mapping IPv4 into the
     * IPv6 space as {@code ::ffff:a.b.c.d} where the two need to agree.
     */
    private static String literal(InetAddress address, boolean ipv6) {
        if (address == null) {
            // An unresolved address should not reach here, but a header claiming the
            // wildcard is better than a malformed one the backend will reject.
            return ipv6 ? "::" : "0.0.0.0";
        }
        String hostAddress = address.getHostAddress();
        if (ipv6 && !(address instanceof Inet6Address)) {
            return "::ffff:" + hostAddress;
        }
        if (address instanceof Inet6Address) {
            // Strip any scope id ("fe80::1%eth0"), which is meaningless to the peer.
            int scope = hostAddress.indexOf('%');
            return scope == -1 ? hostAddress : hostAddress.substring(0, scope);
        }
        return hostAddress;
    }
}
