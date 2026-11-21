package com.eyecrasher.lazodiscs.server;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class VanillaRecordStopper {
    private VanillaRecordStopper() {}

    public static void stopAt(ServerLevel level, BlockPos pos) {
        level.levelEvent(1010, pos, 0);
    }
}
