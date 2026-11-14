package com.eyecrasher.lazodiscs.data;

import java.lang.reflect.InvocationTargetException;
import java.util.Locale;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Keep each native disc intact; read its current song strength through the optional Bukkit API. */
final class DiscSongs {
    static boolean isMusicDisc(ItemStack item) { return item != null && item.getAmount() > 0 && item.getType().isRecord(); }
    static String songKey(ItemStack item) { return null; }
    static void record(ItemStack item, int strength) {}
    static void restore(ItemStack item, String key) {}
    static int comparatorOutput(ItemStack item) {
        try {
            // New default components are available on ItemStack only on Paper, so use its
            // public getter when present; Bukkit ItemMeta exposes explicit components.
            Class<?> songs = Class.forName("org.bukkit.JukeboxSong");
            Class<?> registry = Class.forName("org.bukkit.Registry");
            Object songRegistry = registry.getField("JUKEBOX_SONG").get(null);
            Object song = registry.getMethod("get", org.bukkit.NamespacedKey.class).invoke(songRegistry,
                    org.bukkit.NamespacedKey.minecraft(item.getType().name().substring("MUSIC_DISC_".length()).toLowerCase(Locale.ROOT)));
            if (song != null) return ((Number) songs.getMethod("getComparatorOutput").invoke(song)).intValue();
        } catch (ClassNotFoundException | NoSuchMethodException | NoSuchFieldException olderApi) {
            // Before the public song registry, vanilla outputs are fixed by disc identity.
        } catch (ReflectiveOperationException failure) {
            Throwable cause = failure instanceof InvocationTargetException ? failure.getCause() : failure;
            throw new IllegalStateException("Cannot read native comparator output", cause);
        }
        return VanillaDiscs.comparator(item.getType().name().substring("MUSIC_DISC_".length()).toLowerCase(Locale.ROOT));
    }
}
