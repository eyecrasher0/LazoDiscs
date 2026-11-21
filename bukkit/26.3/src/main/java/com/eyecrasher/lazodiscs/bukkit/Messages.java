package com.eyecrasher.lazodiscs.bukkit;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.json.JSONOptions;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.meta.ItemMeta;

/** Spigot's public chat API retains clickable search results without requiring Paper. */
public final class Messages {
    // Bungee's public API still consumes the camelCase JSON schema on old servers.
    private static final GsonComponentSerializer CHAT = GsonComponentSerializer.builder().editOptions(options -> options
            .value(JSONOptions.EMIT_CLICK_EVENT_TYPE, JSONOptions.ClickEventValueMode.CAMEL_CASE)
            .value(JSONOptions.EMIT_HOVER_EVENT_TYPE, JSONOptions.HoverEventValueMode.CAMEL_CASE)).build();
    private Messages() {}
    public static void send(CommandSender target, Component message) {
        target.spigot().sendMessage(net.md_5.bungee.chat.ComponentSerializer.parse(CHAT.serialize(message)));
    }
    public static Component displayName(ItemMeta meta) {
        return meta.hasDisplayName() ? LegacyComponentSerializer.legacySection().deserialize(meta.getDisplayName()) : null;
    }
    public static void displayName(ItemMeta meta, Component name) {
        meta.setDisplayName(name == null ? null : LegacyComponentSerializer.legacySection().serialize(name));
    }
}
