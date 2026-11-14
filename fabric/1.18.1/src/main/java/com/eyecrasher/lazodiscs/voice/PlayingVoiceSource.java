package com.eyecrasher.lazodiscs.voice;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public interface PlayingVoiceSource {
    void stop();

    /**
     * Moves an already-created Plasmo Voice source. This is important for Sable sub-level blocks:
     * the original BlockPos stays in the technical plot, while the projected real-world position
     * changes as the platform moves.
     */
    default void updatePosition(ServerLevel level, Vec3 projectedPosition) {}
}
