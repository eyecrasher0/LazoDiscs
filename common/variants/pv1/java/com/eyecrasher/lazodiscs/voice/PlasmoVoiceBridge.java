package com.eyecrasher.lazodiscs.voice;
import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.LazoDiscsServerBootstrap;
import com.eyecrasher.lazodiscs.data.CustomDiscData;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;
public final class PlasmoVoiceBridge {
    public static final PlasmoVoiceBridge INSTANCE = new PlasmoVoiceBridge();
    private PlasmoVoiceBridge() {}
    public boolean isInitialized() { return LazoDiscsServerBootstrap.isLoaded(); }
    public void shutdown() { StreamingSession.shutdown(); }
    public PlayingVoiceSource startStaticSource(ServerLevel level, BlockPos pos, CustomDiscData disc, Runnable finished) {
        return startEntitySource(level, -1, Vec3.atCenterOf(pos), disc, finished);
    }
    public PlayingVoiceSource startEntitySource(ServerLevel level, int entityId, Vec3 center, CustomDiscData disc, Runnable finished) {
        UUID source = UUID.randomUUID();
        var position = new java.util.concurrent.atomic.AtomicReference<>(new Position(level, center));
        var stream = new StreamingSession(new LegacyPacketSender(source, task -> level.getServer().execute(task),
                (opus, sequence) -> {
                    Position current = position.get();
                    var entity = entityId < 0 ? null : current.level.getEntity(entityId);
                    Vec3 at = entity == null ? current.center : entity.position();
                    return LegacyAudioPacket.frame(source, current.level.dimension().location().toString(),
                            at.x, at.y, at.z, disc.range(), sequence, opus);
                }, packet -> {
                    Position current = position.get();
                    Vec3 at = packet.kind == LegacyAudioPacket.FRAME ? new Vec3(packet.x, packet.y, packet.z) : current.center;
                    LegacyNetwork.broadcast(current.level, at, disc.range(), packet.encode());
                }), failure -> level.getServer().execute(() -> {
            if (failure != null) {
                LazoDiscs.LOGGER.warn("Could not play '{}': {}", disc.title(), failure);
                for (var player : level.players()) if (player.position().distanceToSqr(center) <= (double) disc.range() * disc.range())
                    player.sendMessage(LazoDiscsText.audioLoadFailed(disc.title(), failure), new UUID(0, 0));
            }
            if (finished != null) finished.run();
        }));
        stream.start(disc);
        return new PlayingVoiceSource() {
            @Override public void stop() { stream.close(); }
            @Override public void updatePosition(ServerLevel updated, Vec3 projected) { position.set(new Position(updated, projected)); }
        };
    }
    private record Position(ServerLevel level, Vec3 center) {}
}
