package com.eyecrasher.lazodiscs.server;

import net.minecraft.util.math.BlockPos;
import net.minecraft.world.server.ServerWorld;

public final class VanillaRecordStopper {
    private VanillaRecordStopper() {}

    public static void stopAt(ServerWorld level, BlockPos pos) {
        level.levelEvent(1010, pos, 0);
    }
}
