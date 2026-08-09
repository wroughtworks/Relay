package dev.relay.net.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

import javax.crypto.Cipher;

/** Encrypts outbound bytes. The last handler a frame passes through on its way out. */
public final class CipherEncoder extends MessageToByteEncoder<ByteBuf> {

    private final PacketCipher cipher;

    public CipherEncoder(Cipher cipher) {
        super(false);
        this.cipher = new PacketCipher(cipher);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) throws Exception {
        out.writeBytes(msg);
        cipher.process(out);
    }
}
