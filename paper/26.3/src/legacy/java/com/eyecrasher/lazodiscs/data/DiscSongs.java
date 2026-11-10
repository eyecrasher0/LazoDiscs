package com.eyecrasher.lazodiscs.data;

import org.bukkit.inventory.ItemStack;

import java.util.Locale;

/** Legacy items keep their vanilla type and store the recording only in PDC. */
final class DiscSongs {
    static boolean isMusicDisc(ItemStack item) {
        return item != null && item.getAmount() > 0 && item.getType().isRecord();
    }

    static String songKey(ItemStack item) {
        return null;
    }

    static void record(ItemStack item, int strength) {}

    static void restore(ItemStack item, String key) {}

    static int comparatorOutput(ItemStack item) {
        return VanillaDiscs.comparator(
                item.getType().name().substring("MUSIC_DISC_".length()).toLowerCase(Locale.ROOT));
    }
}
