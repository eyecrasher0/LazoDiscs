package com.eyecrasher.lazodiscs.server;

import com.eyecrasher.lazodiscs.compat.SablePositionCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

public final class VanillaRecordStopper {
    private VanillaRecordStopper() {}

    public static void stopVanillaRecordsNear(ServerLevel level, BlockPos pos, double radius) {
        double max = radius * radius;
        ClientboundStopSoundPacket packet =
                new ClientboundStopSoundPacket(null, SoundSource.RECORDS);
        Vec3 projected = SablePositionCompat.projectJukeboxCenter(level, pos);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(projected.x, projected.y, projected.z) <= max) {
                player.connection.send(packet);
            }
        }
    }
}
