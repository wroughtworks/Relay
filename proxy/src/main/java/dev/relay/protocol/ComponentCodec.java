package dev.relay.protocol;

import dev.relay.protocol.nbt.NbtWriter;
import io.netty.buffer.ByteBuf;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

/**
 * Writes text components in whichever encoding the negotiated protocol expects.
 *
 * <p>There are two on-wire encodings in Relay's supported range:
 * <ul>
 *   <li><b>JSON string</b> &mdash; login-state disconnects at every version, and all
 *       components on 1.20.2.</li>
 *   <li><b>Network NBT</b> &mdash; configuration- and play-state components from
 *       1.20.3 onward.</li>
 * </ul>
 * Login disconnects are always JSON because they precede registry sync, where the
 * client has nothing to resolve an NBT component against.
 */
public final class ComponentCodec {

    private static final GsonComponentSerializer GSON = GsonComponentSerializer.gson();

    private ComponentCodec() {
    }

    public static String toJson(Component component) {
        return GSON.serialize(component);
    }

    public static Component fromJson(String json) {
        return GSON.deserialize(json);
    }

    /** Always JSON, regardless of version. Use for login-state disconnects. */
    public static void writeJson(ByteBuf buf, Component component) {
        ProtocolUtils.writeString(buf, toJson(component));
    }

    /** JSON on 1.20.2, network NBT from 1.20.3 onward. */
    public static void write(ByteBuf buf, Component component, ProtocolVersion version) {
        if (version.atLeast(ProtocolVersion.MINECRAFT_1_20_3)) {
            NbtWriter.writeRoot(buf, GSON.serializeToTree(component));
        } else {
            ProtocolUtils.writeString(buf, toJson(component));
        }
    }
}
