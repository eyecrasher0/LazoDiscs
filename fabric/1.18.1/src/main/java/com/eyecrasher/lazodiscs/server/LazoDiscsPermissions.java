package com.eyecrasher.lazodiscs.server;

import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.player.Player;

public final class LazoDiscsPermissions {
    private LazoDiscsPermissions() {}

    public static boolean canBurn(CommandSourceStack source) {
        return allowed(
                source,
                LazoDiscsConfig.REQUIRE_PERMISSION_FOR_BURN_COMMAND.get(),
                LazoDiscsConfig.BURN_PERMISSION_LEVEL.get());
    }

    public static boolean canErase(CommandSourceStack source) {
        return allowed(
                source,
                LazoDiscsConfig.REQUIRE_PERMISSION_FOR_ERASE_COMMAND.get(),
                LazoDiscsConfig.ERASE_PERMISSION_LEVEL.get());
    }

    public static boolean canSearch(CommandSourceStack source) {
        return allowed(
                source,
                LazoDiscsConfig.REQUIRE_PERMISSION_FOR_SEARCH_COMMAND.get(),
                LazoDiscsConfig.SEARCH_PERMISSION_LEVEL.get());
    }

    public static boolean canPlay(Player player) {
        if (!LazoDiscsConfig.REQUIRE_PERMISSION_FOR_PLAY.get()) {
            return true;
        }
        return player != null && player.hasPermissions(LazoDiscsConfig.PLAY_PERMISSION_LEVEL.get());
    }

    private static boolean allowed(CommandSourceStack source, boolean required, int level) {
        return !required || source != null && source.hasPermission(level);
    }
}
