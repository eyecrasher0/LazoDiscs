package com.eyecrasher.lazodiscs.voice;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
public final class LegacyNetwork {
    public static final ResourceLocation CHANNEL = new ResourceLocation("lazodiscs", "legacy");
    private static final Set<UUID> CLIENTS = ConcurrentHashMap.newKeySet();
    private static boolean initialized;
    private LegacyNetwork() {}
    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        ServerPlayNetworking.registerGlobalReceiver(CHANNEL, (server, player, handler, buffer, sender) -> {
            if (buffer.readableBytes() != 3) return;
            byte[] bytes = new byte[3]; buffer.readBytes(bytes);
            try { if (LegacyAudioPacket.decode(bytes).kind == LegacyAudioPacket.HELLO) server.execute(() -> CLIENTS.add(player.getUUID())); }
            catch (java.io.IOException ignored) {}
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> CLIENTS.remove(handler.player.getUUID()));
    }
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
                (player, bytes) -> {
            var payload = PacketByteBufs.create(); payload.writeBytes(bytes);
            ServerPlayNetworking.send(player, CHANNEL, payload);
        });
    }
}
