package com.eyecrasher.lazodiscs.voice;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.network.NetworkEvent;

/** Client-only channel; server bookkeeping belongs to the installed Bukkit plugin. */
public final class LegacyNetwork {
    public static final ResourceLocation CHANNEL = new ResourceLocation("lazodiscs", "legacy");
    private static SimpleChannel network;
    private LegacyNetwork() {}
    public static synchronized void initialize() {
        if (network != null) return;
        network = NetworkRegistry.newSimpleChannel(CHANNEL, () -> "1", version -> true, version -> true);
        network.registerMessage(0, Payload.class,
                (message, buffer) -> buffer.writeBytes(message.bytes, 1, message.bytes.length - 1), buffer -> {
                    int length = buffer.readableBytes();
                    if (length > 4607) throw new IllegalArgumentException("Oversized audio packet");
                    byte[] bytes = new byte[length + 1];
                    buffer.readBytes(bytes, 1, length);
                    return new Payload(bytes);
                }, (message, context) -> {
                    NetworkEvent.Context ctx = context.get();
                    ctx.enqueueWork(() -> {
                        try { LegacyClient.accept(LegacyAudioPacket.decode(message.bytes)); }
                        catch (java.io.IOException malformed) { /* Discard invalid server audio. */ }
                    });
                    ctx.setPacketHandled(true);
                });
    }
    private static final class Payload {
        final byte[] bytes;
        Payload(byte[] bytes) { this.bytes = bytes; }
    }
}
