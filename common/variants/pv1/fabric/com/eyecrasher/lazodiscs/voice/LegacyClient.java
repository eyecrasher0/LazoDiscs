package com.eyecrasher.lazodiscs.voice;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
public final class LegacyClient implements ClientModInitializer {
    private static final ResourceLocation CHANNEL = new ResourceLocation("lazodiscs", "legacy");
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(CHANNEL, (client, handler, buffer, response) -> {
            if (buffer.readableBytes() > 4608) return;
            byte[] bytes = new byte[buffer.readableBytes()]; buffer.readBytes(bytes);
            try {
                var packet = LegacyAudioPacket.decode(bytes);
                client.execute(() -> { if (client.level != null) LegacyClientAudio.accept(packet, client.level.dimension().location().toString(), p -> new Vec3(p.x, p.y, p.z)); });
            } catch (java.io.IOException malformed) {}
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            var payload = PacketByteBufs.create(); payload.writeBytes(LegacyAudioPacket.hello().encode());
            ClientPlayNetworking.send(CHANNEL, payload);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> LegacyClientAudio.prune(client.level != null,
                client.level == null ? null : client.level.dimension().location().toString()));
    }
}
