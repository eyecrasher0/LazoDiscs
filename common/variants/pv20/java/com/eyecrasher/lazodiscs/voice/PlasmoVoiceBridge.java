package com.eyecrasher.lazodiscs.voice;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.data.CustomDiscData;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** The older Voice 2.0 API has no AudioSender; keep its packet sender behind one adapter. */
public final class PlasmoVoiceBridge {
    public static final PlasmoVoiceBridge INSTANCE = new PlasmoVoiceBridge();
    private volatile Pv2Transport transport;
    private PlasmoVoiceBridge() {}
    public void initialize(Object server, Object addon) {
        Pv2Transport initialized = new Pv2Transport(server, addon);
        StreamingSession.enable();
        transport = initialized;
    }
    public boolean isInitialized() { return transport != null; }
    public void shutdown() { transport = null; StreamingSession.shutdown(); }
    public PlayingVoiceSource startStaticSource(ServerLevel level, BlockPos pos, CustomDiscData disc, Runnable finished) {
        return start(level, com.eyecrasher.lazodiscs.compat.SablePositionCompat.projectJukeboxCenter(level, pos), null, disc, finished);
    }
    public PlayingVoiceSource startEntitySource(ServerLevel level, int entityId, Vec3 position, CustomDiscData disc, Runnable finished) {
        return start(level, position, level.getEntity(entityId), disc, finished);
    }
    private PlayingVoiceSource start(ServerLevel level, Vec3 position, Object entity, CustomDiscData disc, Runnable finished) {
        Pv2Transport current = transport;
        if (current == null) throw new IllegalStateException("Plasmo Voice is not ready");
        var sink = entity == null ? current.source(level, position.x, position.y, position.z, disc.title(), disc.range()) : current.entitySource(entity, disc.title(), disc.range());
        PlaybackProbe.sourceRegistered(disc.id());
        var stream = new StreamingSession(sink, failure -> level.getServer().execute(() -> {
            if (failure != null) {
                LazoDiscs.LOGGER.warn("Could not play '{}': {}", disc.title(), failure);
                for (var player : level.players()) if (player.position().distanceToSqr(position) <= (double) disc.range() * disc.range())
                    player.sendSystemMessage(LazoDiscsText.audioLoadFailed(disc.title(), failure));
            }
            if (finished != null) finished.run();
        }));
        stream.start(disc);
        return new PlayingVoiceSource() {
            @Override public void stop() { stream.close(); }
            @Override public void updatePosition(ServerLevel updated, Vec3 projected) {
                if (sink instanceof Pv2Transport.PositionalSink positional) positional.move(updated, projected.x, projected.y, projected.z);
            }
        };
    }
}
