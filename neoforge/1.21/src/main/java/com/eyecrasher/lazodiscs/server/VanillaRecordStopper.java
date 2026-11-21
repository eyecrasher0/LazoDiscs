package com.eyecrasher.lazodiscs.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LevelEvent;

public final class VanillaRecordStopper {
    private VanillaRecordStopper() {}

    public static void stopAt(ServerLevel level, BlockPos pos) {
        level.levelEvent(LevelEvent.SOUND_STOP_JUKEBOX_SONG, pos, 0);
    }
}
