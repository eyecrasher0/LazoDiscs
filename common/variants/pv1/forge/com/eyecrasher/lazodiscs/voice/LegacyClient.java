package com.eyecrasher.lazodiscs.voice;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
@EventBusSubscriber(modid = "lazodiscs", value = Dist.CLIENT)
public final class LegacyClient {
    private static Object connection;
    private LegacyClient() {}
    public static void accept(LegacyAudioPacket packet) {
        var client = Minecraft.getInstance();
        if (client.level != null) LegacyClientAudio.accept(packet, client.level.dimension().location().toString(), p -> new Vec3(p.x, p.y, p.z));
    }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var client = Minecraft.getInstance(); var current = client.getConnection();
        if (current != null && client.level != null && current != connection) {
            connection = current;
            current.send(new ServerboundCustomPayloadPacket(LegacyNetwork.CHANNEL, new FriendlyByteBuf(Unpooled.wrappedBuffer(LegacyAudioPacket.hello().encode()))));
        }
        if (current == null) connection = null;
        LegacyClientAudio.prune(client.level != null,
                client.level == null ? null : client.level.dimension().location().toString());
    }
}
