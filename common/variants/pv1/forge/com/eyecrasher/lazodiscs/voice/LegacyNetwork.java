package com.eyecrasher.lazodiscs.voice;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.network.NetworkEvent;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.FriendlyByteBuf;
import io.netty.buffer.Unpooled;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = "lazodiscs")
public final class LegacyNetwork {
    public static final ResourceLocation CHANNEL = new ResourceLocation("lazodiscs", "legacy");
    private static final Set<UUID> CLIENTS = ConcurrentHashMap.newKeySet();
    private static SimpleChannel network;
    private LegacyNetwork() {}
    public static synchronized void initialize() {
        if (network != null) return;
        network = NetworkRegistry.newSimpleChannel(CHANNEL, () -> "1", version -> true, version -> true);
        network.registerMessage(0, Payload.class, (message, buffer) -> buffer.writeBytes(message.bytes, 1, message.bytes.length - 1), buffer -> {
            int length = buffer.readableBytes();
            if (length > 4607) throw new IllegalArgumentException("Oversized audio packet");
            byte[] bytes = new byte[length + 1]; buffer.readBytes(bytes, 1, length); return new Payload(bytes);
        }, (message, context) -> {
            NetworkEvent.Context ctx = context.get();
            ctx.enqueueWork(() -> {
                try {
                    var packet = LegacyAudioPacket.decode(message.bytes);
                    var player = ctx.getSender();
                    if (player != null) { if (packet.kind == LegacyAudioPacket.HELLO) CLIENTS.add(player.getUUID()); }
                    else net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> LegacyClient.accept(packet));
                } catch (java.io.IOException malformed) {}
            });
            ctx.setPacketHandled(true);
        });
    }
    @net.minecraftforge.eventbus.api.SubscribeEvent public static void quit(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) { CLIENTS.remove(event.getPlayer().getUUID()); }
    public static void reset() { CLIENTS.clear(); }
    public static void broadcast(ServerLevel level, Vec3 center, int range, LegacyAudioPacket packet, LegacyAudience audience) {
        var audible = new java.util.ArrayList<UUID>();
        if (packet.kind == LegacyAudioPacket.FRAME) {
            for (var player : level.players()) {
                if (CLIENTS.contains(player.getUUID()) && LegacyVoiceSessions.hasSession(player.getUUID())
                        && player.position().distanceToSqr(center) <= (double) range * range)
                    audible.add(player.getUUID());
            }
        }
        audience.deliver(packet, audible, id ->
                CLIENTS.contains(id)
                        ? level.getServer().getPlayerList().getPlayer(id) : null,
                (player, bytes) -> player.connection.send(
                        new ClientboundCustomPayloadPacket(CHANNEL, new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes)))));
    }
    private static final class Payload { final byte[] bytes; Payload(byte[] bytes) { this.bytes = bytes; } }
}
