package dev.relay.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.haproxy.HAProxyMessageEncoder;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Round-trips the PROXY headers Relay emits through Netty's own decoder.
 *
 * <p>Encoding a header the far end cannot parse is indistinguishable, from Relay's side,
 * from encoding a correct one: the backend simply never answers. Decoding each header
 * back is the only way to know it is well formed without a real Paper instance.
 */
class ProxyProtocolTest {

    @Test
    void ipv4HeaderRoundTrips() throws Exception {
        HAProxyMessage decoded = roundTrip(
                new InetSocketAddress(InetAddress.getByName("203.0.113.7"), 51234),
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 25566));

        assertEquals(HAProxyProtocolVersion.V2, decoded.protocolVersion());
        assertEquals(HAProxyCommand.PROXY, decoded.command());
        assertEquals(HAProxyProxiedProtocol.TCP4, decoded.proxiedProtocol());
        assertEquals("203.0.113.7", decoded.sourceAddress());
        assertEquals(51234, decoded.sourcePort());
        assertEquals("127.0.0.1", decoded.destinationAddress());
        assertEquals(25566, decoded.destinationPort());
        decoded.release();
    }

    @Test
    void ipv6HeaderRoundTrips() throws Exception {
        HAProxyMessage decoded = roundTrip(
                new InetSocketAddress(InetAddress.getByName("2001:db8::1"), 51234),
                new InetSocketAddress(InetAddress.getByName("2001:db8::2"), 25566));

        assertEquals(HAProxyProxiedProtocol.TCP6, decoded.proxiedProtocol());
        assertEquals(51234, decoded.sourcePort());
        assertNotNull(decoded.sourceAddress());
        decoded.release();
    }

    /**
     * A dual-stack host routinely pairs an IPv6 player with an IPv4 backend. The PROXY
     * header requires one family for both ends, so the narrower address is widened
     * rather than the header being emitted malformed.
     */
    @Test
    void mixedFamiliesAreWidenedToIpv6() throws Exception {
        HAProxyMessage decoded = roundTrip(
                new InetSocketAddress(InetAddress.getByName("2001:db8::1"), 51234),
                new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 25566));

        assertEquals(HAProxyProxiedProtocol.TCP6, decoded.proxiedProtocol());
        assertEquals(25566, decoded.destinationPort());
        // Compared as an address, not as text: an IPv4-mapped address has several valid
        // renderings ("::ffff:127.0.0.1", "0:0:0:0:0:ffff:7f00:1") and which one comes
        // back is the decoder's choice, not part of the contract.
        assertEquals(InetAddress.getByName("127.0.0.1"),
                InetAddress.getByName(decoded.destinationAddress()));
        decoded.release();
    }

    /** A scope id is local to this host and meaningless to the peer. */
    @Test
    void linkLocalScopeIdsAreStripped() throws Exception {
        HAProxyMessage decoded = roundTrip(
                new InetSocketAddress(InetAddress.getByName("fe80::1%1"), 51234),
                new InetSocketAddress(InetAddress.getByName("2001:db8::2"), 25566));

        assertEquals(HAProxyProxiedProtocol.TCP6, decoded.proxiedProtocol());
        org.junit.jupiter.api.Assertions.assertFalse(decoded.sourceAddress().contains("%"),
                "scope id leaked into the header: " + decoded.sourceAddress());
        decoded.release();
    }

    private static HAProxyMessage roundTrip(InetSocketAddress source, InetSocketAddress destination) {
        EmbeddedChannel encoder = new EmbeddedChannel(HAProxyMessageEncoder.INSTANCE);
        EmbeddedChannel decoder = new EmbeddedChannel(new HAProxyMessageDecoder());
        try {
            encoder.writeOutbound(ProxyProtocol.header(source, destination));
            decoder.writeInbound(encoder.<ByteBuf>readOutbound());

            HAProxyMessage decoded = decoder.readInbound();
            assertNotNull(decoded, "the encoded header could not be decoded");
            return decoded;
        } finally {
            encoder.finishAndReleaseAll();
            decoder.finishAndReleaseAll();
        }
    }
}
