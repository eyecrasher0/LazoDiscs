package com.eyecrasher.lazodiscs.event;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.compat.sophisticatedbackpacks.LazoDiscsDiscHandler;
import com.eyecrasher.lazodiscs.data.DiscDataUtil;
import com.eyecrasher.lazodiscs.server.SourceKey;
import com.eyecrasher.lazodiscs.voice.PlasmoVoiceBridge;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;

import java.util.HashMap;
import java.util.Map;

public final class JukeboxEvents {
    private static final Map<SourceKey, ServerLevel> pendingResyncs = new HashMap<>();

    private JukeboxEvents() {}

    public static void register() {
        PlayerBlockBreakEvents.AFTER.register(
                (world, player, pos, state, blockEntity) -> {
                    if (world instanceof ServerLevel level && state.is(Blocks.JUKEBOX)) {
                        LazoDiscs.playback().stopAt(level, pos, "block-break");
                    }
                });

        ServerChunkEvents.CHUNK_LOAD.register(
                (level, chunk) -> {
                    for (var blockEntity : chunk.getBlockEntities().values()) {
                        if (blockEntity instanceof JukeboxBlockEntity jukebox
                                && DiscDataUtil.hasCustomDisc(jukebox.getRecord())) {
                            pendingResyncs.put(
                                    new SourceKey(
                                            level.dimension(), jukebox.getBlockPos().immutable()),
                                    level);
                        }
                    }
                });

        ServerChunkEvents.CHUNK_UNLOAD.register(
                (level, chunk) -> {
                    pendingResyncs
                            .entrySet()
                            .removeIf(
                                    entry ->
                                            entry.getValue() == level
                                                    && new ChunkPos(entry.getKey().pos())
                                                            .equals(chunk.getPos()));
                    LazoDiscs.playback().stopChunk(level, chunk.getPos(), "chunk-unload");
                });

        ServerTickEvents.END_SERVER_TICK.register(
                server -> {
                    resyncLoadedJukeboxes();
                    for (ServerLevel level : server.getAllLevels()) {
                        LazoDiscs.playback().tickLevel(level);
                    }
                    LazoDiscsDiscHandler.onServerTick(server);
                });

        ServerLifecycleEvents.SERVER_STOPPING.register(
                server -> {
                    pendingResyncs.clear();
                    LazoDiscs.playback().stopAll("server-stopping");
                    LazoDiscsDiscHandler.stopAll("server-stopping");
                });
    }

    private static void resyncLoadedJukeboxes() {
        // Keep startup requests until Plasmo has initialized its source line.
        if (!PlasmoVoiceBridge.INSTANCE.isInitialized()) return;
        var it = pendingResyncs.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            it.remove();
            ServerLevel level = entry.getValue();
            if (level.hasChunkAt(entry.getKey().pos())) {
                LazoDiscs.playback()
                        .resyncFromBlockEntity(level, entry.getKey().pos(), "chunk-load");
            }
        }
    }
}
