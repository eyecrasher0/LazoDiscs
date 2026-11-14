package com.eyecrasher.lazodiscs.bukkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.meta.ItemMeta;

/** Spigot's public chat API retains clickable search results without requiring Paper. */
public final class Messages {
    private Messages() {}
    public static void send(CommandSender target, Component message) {
        target.spigot().sendMessage(net.md_5.bungee.chat.ComponentSerializer.parse(GsonComponentSerializer.gson().serialize(message)));
    }
    public static String worldKey(org.bukkit.World world) {
        try { return world.getClass().getMethod("getKey").invoke(world).toString(); }
        catch (ReflectiveOperationException oldApi) {
            return switch (world.getEnvironment()) {
                case NETHER -> "minecraft:the_nether";
                case THE_END -> "minecraft:the_end";
                default -> "minecraft:overworld";
            };
        }
    }
    public static Component displayName(ItemMeta meta) {
        return meta.hasDisplayName() ? LegacyComponentSerializer.legacySection().deserialize(meta.getDisplayName()) : null;
    }
    public static void displayName(ItemMeta meta, Component name) {
        meta.setDisplayName(name == null ? null : LegacyComponentSerializer.legacySection().serialize(name));
    }
}
