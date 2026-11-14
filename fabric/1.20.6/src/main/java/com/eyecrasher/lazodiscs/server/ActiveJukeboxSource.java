package com.eyecrasher.lazodiscs.server;

import com.eyecrasher.lazodiscs.data.CustomDiscData;
import com.eyecrasher.lazodiscs.voice.PlayingVoiceSource;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public record ActiveJukeboxSource(
        CustomDiscData disc,
        PlayingVoiceSource source,
        java.util.concurrent.atomic.AtomicBoolean dynamicPosition) {
    public void stop() {
        source.stop();
    }

    public void updatePosition(ServerLevel level, Vec3 projectedPosition) {
        source.updatePosition(level, projectedPosition);
    }

    public boolean isDynamicPosition() {
        return dynamicPosition.get();
    }

    public void markDynamicPosition() {
        dynamicPosition.set(true);
    }
}
