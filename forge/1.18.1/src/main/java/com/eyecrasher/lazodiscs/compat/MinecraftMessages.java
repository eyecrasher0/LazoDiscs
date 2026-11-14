package com.eyecrasher.lazodiscs.compat;

import net.minecraft.Util;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** System-chat adapter for Minecraft versions before the signed-chat API. */
public final class MinecraftMessages {
    private MinecraftMessages() {}

    public static void send(ServerPlayer player, Component message) {
        player.sendMessage(message, ChatType.SYSTEM, Util.NIL_UUID);
    }
}
