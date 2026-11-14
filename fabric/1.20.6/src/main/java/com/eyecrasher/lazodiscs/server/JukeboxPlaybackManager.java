package com.eyecrasher.lazodiscs.server;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.compat.SablePositionCompat;
import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;
import com.eyecrasher.lazodiscs.data.CustomDiscData;
import com.eyecrasher.lazodiscs.data.DiscDataUtil;
import com.eyecrasher.lazodiscs.voice.PlasmoVoiceBridge;
import com.eyecrasher.lazodiscs.voice.PlayingVoiceSource;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class JukeboxPlaybackManager {
    public static final JukeboxPlaybackManager INSTANCE = new JukeboxPlaybackManager();

    private final Map<SourceKey, ActiveJukeboxSource> active = new ConcurrentHashMap<>();

    private JukeboxPlaybackManager() {}

    public void onJukeboxItemChanged(
            ServerLevel level, BlockPos pos, ItemStack newStack, String reason) {
        // Do not spam StopSoundPacket several times for one insertion. The new start() call
        // will stop vanilla RECORDS once after it knows whether a custom disc is actually present.
        stopAt(level, pos, reason + ":before-change", false);

        DiscDataUtil.read(newStack)
                .ifPresent(
                        disc -> {
                            if (!isValidJukebox(level, pos)) return;
                            start(level, pos, disc, reason);
                        });
    }

    public void resyncFromBlockEntity(ServerLevel level, BlockPos pos, String reason) {
        if (!(level.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox)) {
            stopAt(level, pos, reason + ":no-be");
            return;
        }

        ItemStack stack = jukebox.getTheItem();
        if (DiscDataUtil.hasCustomDisc(stack)) {
            onJukeboxItemChanged(level, pos, stack, reason);
        } else {
            stopAt(level, pos, reason + ":not-custom-disc");
        }
    }

    public void start(ServerLevel level, BlockPos pos, CustomDiscData disc, String reason) {
        SourceKey sourceKey = new SourceKey(level.dimension(), pos.immutable());
        ActiveJukeboxSource existing = active.get(sourceKey);

        // Sable sub-level safety gate: admins can disable playback on moving platforms.
        if (SablePositionCompat.isProbablySubLevel(pos)
                && !LazoDiscsConfig.ALLOW_PLAYBACK_ON_SABLE_PLATFORMS.get()) {
            LazoDiscs.LOGGER.info(
                    "Refusing to start LazoDisc '{}' at {} — block is on a Sable sub-level "
                            + "and allowPlaybackOnSablePlatforms is disabled in config.",
                    disc.title(),
                    pos.toShortString());
            VanillaRecordStopper.stopVanillaRecordsNear(level, pos, 4.0D);
            return;
        }

        if (existing != null && existing.disc().equals(disc)) {
            LazoDiscs.LOGGER.debug(
                    "Ignoring duplicate LazoDisc start for '{}' at {} ({})",
                    disc.title(),
                    pos.toShortString(),
                    reason);
            VanillaRecordStopper.stopVanillaRecordsNear(level, pos, 4.0D);
            return;
        }

        ActiveJukeboxSource old = active.remove(sourceKey);
        if (old != null) {
            try {
                old.stop();
            } catch (Throwable e) {
                LazoDiscs.LOGGER.warn(
                        "Failed to stop old LazoDisc at {}: {}", pos.toShortString(), e.toString());
            }
        }

        try {
            Vec3 center = Vec3.atCenterOf(pos);
            Vec3 projected = SablePositionCompat.projectJukeboxCenter(level, pos);
            boolean dynamicPosition =
                    SablePositionCompat.isProbablySubLevel(pos)
                            || projected.distanceToSqr(center) > 0.0001D;

            var sourceRef = new java.util.concurrent.atomic.AtomicReference<PlayingVoiceSource>();
            var source =
                    PlasmoVoiceBridge.INSTANCE.startStaticSource(
                            level,
                            pos,
                            disc,
                            () -> finishAt(level, pos, sourceRef.get(), "track-ended"));
            sourceRef.set(source);
            active.put(
                    sourceKey,
                    new ActiveJukeboxSource(
                            disc,
                            source,
                            new java.util.concurrent.atomic.AtomicBoolean(dynamicPosition)));
            // Stop vanilla record sound that may have started from the original music disc.
            VanillaRecordStopper.stopVanillaRecordsNear(level, pos, 4.0D);
            LazoDiscs.LOGGER.info(
                    "Started LazoDisc '{}' at {} ({}, dynamicPosition={})",
                    disc.title(),
                    pos.toShortString(),
                    reason,
                    dynamicPosition);
        } catch (Throwable e) {
            LazoDiscs.LOGGER.warn(
                    "Failed to start LazoDisc at {}: {}", pos.toShortString(), e.toString());
        }
    }

    private void finishAt(
            ServerLevel level, BlockPos pos, PlayingVoiceSource source, String reason) {
        SourceKey sourceKey = new SourceKey(level.dimension(), pos.immutable());
        ActiveJukeboxSource current = active.get(sourceKey);
        if (current == null || current.source() != source) {
            return;
        }
        if (active.remove(sourceKey, current)) {
            LazoDiscs.LOGGER.info(
                    "Finished LazoDisc '{}' at {} ({})",
                    current.disc().title(),
                    pos.toShortString(),
                    reason);
        }
    }

    public void stopAt(ServerLevel level, BlockPos pos, String reason) {
        stopAt(level, pos, reason, true);
    }

    private void stopAt(
            ServerLevel level, BlockPos pos, String reason, boolean stopVanillaRecords) {
        ActiveJukeboxSource old = active.remove(new SourceKey(level.dimension(), pos.immutable()));
        if (old != null) {
            try {
                old.stop();
            } catch (Throwable e) {
                LazoDiscs.LOGGER.warn(
                        "Failed to stop LazoDisc at {}: {}", pos.toShortString(), e.toString());
            }
            LazoDiscs.LOGGER.info("Stopped LazoDisc at {} ({})", pos.toShortString(), reason);
        }
        if (stopVanillaRecords) {
            VanillaRecordStopper.stopVanillaRecordsNear(level, pos, 4.0D);
        }
    }

    public void stopChunk(ServerLevel level, ChunkPos chunkPos, String reason) {
        Iterator<Map.Entry<SourceKey, ActiveJukeboxSource>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<SourceKey, ActiveJukeboxSource> e = it.next();
            SourceKey key = e.getKey();
            if (key.dimension().equals(level.dimension())
                    && new ChunkPos(key.pos()).equals(chunkPos)) {
                it.remove();
                try {
                    e.getValue().stop();
                } catch (Throwable ex) {
                    LazoDiscs.LOGGER.warn(
                            "Failed to stop LazoDisc during chunk unload: {}", ex.toString());
                }
            }
        }
    }

    public void tickLevel(ServerLevel level) {
        // Not a world scan: only already-playing LazoDiscs are updated.
        // Normal world jukeboxes are static; only Sable/sub-level sources need repeated position
        // updates.
        long gameTime = level.getGameTime();
        int validationInterval = Math.max(1, LazoDiscsConfig.VALIDATION_INTERVAL_TICKS.get());
        boolean validate = gameTime % validationInterval == 0;

        Iterator<Map.Entry<SourceKey, ActiveJukeboxSource>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<SourceKey, ActiveJukeboxSource> e = it.next();
            SourceKey key = e.getKey();
            if (!key.dimension().equals(level.dimension())) continue;

            if (validate && !isStillValidCustomJukebox(level, key.pos())) {
                it.remove();
                e.getValue().stop();
                continue;
            }

            ActiveJukeboxSource activeSource = e.getValue();
            // Re-check sub-level status: a jukebox that started as a normal block may have
            // been swept into a Sable sub-level by the Physics Assembler.
            if (!activeSource.dynamicPosition().get()
                    && SablePositionCompat.isProbablySubLevel(key.pos())) {
                activeSource.markDynamicPosition();
            }
            if (activeSource.isDynamicPosition()) {
                // Moving Sable / Create Aeronautics assemblies must update every tick, otherwise
                // the Plasmo source audibly lags behind the flying platform.
                Vec3 projected = SablePositionCompat.projectJukeboxCenter(level, key.pos());
                activeSource.updatePosition(level, projected);
            }
        }
    }

    /**
     * Called from SablePhysicsEvents after every Sable physics step (~60 Hz) for low-latency
     * position updates on moving platforms.
     */
    public void onSablePostPhysicsTick(ServerLevel level) {
        if (active.isEmpty()) return;
        for (Map.Entry<SourceKey, ActiveJukeboxSource> e : active.entrySet()) {
            SourceKey key = e.getKey();
            if (!key.dimension().equals(level.dimension())) continue;
            ActiveJukeboxSource activeSource = e.getValue();
            if (!activeSource.isDynamicPosition()) {
                if (!SablePositionCompat.isProbablySubLevel(key.pos())) continue;
                activeSource.markDynamicPosition();
            }
            try {
                Vec3 projected = SablePositionCompat.projectJukeboxCenter(level, key.pos());
                activeSource.updatePosition(level, projected);
            } catch (Throwable t) {
                LazoDiscs.LOGGER.debug(
                        "SablePostPhysicsTick position update failed: {}", t.toString());
            }
        }
    }

    public void stopAll(String reason) {
        for (ActiveJukeboxSource source : active.values()) {
            try {
                source.stop();
            } catch (Throwable e) {
                LazoDiscs.LOGGER.warn("Failed to stop LazoDisc during stopAll: {}", e.toString());
            }
        }
        active.clear();
        LazoDiscs.LOGGER.info("Stopped all LazoDisc sources ({})", reason);
    }

    public void pruneInvalid(ServerLevel level) {
        // This is NOT a world scan. It only validates already-active sources.
        Iterator<Map.Entry<SourceKey, ActiveJukeboxSource>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<SourceKey, ActiveJukeboxSource> e = it.next();
            SourceKey key = e.getKey();
            if (!key.dimension().equals(level.dimension())) continue;
            if (!isStillValidCustomJukebox(level, key.pos())) {
                it.remove();
                e.getValue().stop();
            }
        }
    }

    private boolean isStillValidCustomJukebox(ServerLevel level, BlockPos pos) {
        if (!isValidJukebox(level, pos)) return false;
        return level.getBlockEntity(pos) instanceof JukeboxBlockEntity jukebox
                && DiscDataUtil.hasCustomDisc(jukebox.getTheItem());
    }

    private boolean isValidJukebox(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.JUKEBOX)
                && level.getBlockEntity(pos) instanceof JukeboxBlockEntity;
    }
}
