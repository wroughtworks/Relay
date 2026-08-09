package dev.relay.net.pipeline;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;

import javax.crypto.Cipher;
import java.util.List;

/** Decrypts inbound bytes. Sits at the head of the pipeline, ahead of framing. */
public final class CipherDecoder extends MessageToMessageDecoder<ByteBuf> {

    private final PacketCipher cipher;

    public CipherDecoder(Cipher cipher) {
        this.cipher = new PacketCipher(cipher);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        cipher.process(in);
        out.add(in.retain());
    }
}
