package com.eyecrasher.lazodiscs.server;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public record SourceKey(ResourceKey<Level> dimension, BlockPos pos) {}
