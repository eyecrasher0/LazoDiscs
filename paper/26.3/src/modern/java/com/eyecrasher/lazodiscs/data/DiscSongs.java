package com.eyecrasher.lazodiscs.data;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.JukeboxPlayable;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;

import org.bukkit.JukeboxSong;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

/** Public Paper component adapter; no CraftBukkit or Minecraft internals. */
final class DiscSongs {
    static boolean isMusicDisc(ItemStack item) {
        return item != null && !item.isEmpty() && item.hasData(DataComponentTypes.JUKEBOX_PLAYABLE);
    }

    static String songKey(ItemStack item) {
        return item.getData(DataComponentTypes.JUKEBOX_PLAYABLE).jukeboxSong().getKey().toString();
    }

    static void record(ItemStack item, int strength) {
        JukeboxSong silent = song("lazodiscs:recording_" + strength);
        if (silent == null)
            throw new IllegalStateException("LazoDiscs song registry is unavailable");
        item.setData(DataComponentTypes.JUKEBOX_PLAYABLE, JukeboxPlayable.jukeboxPlayable(silent));
    }

    static void restore(ItemStack item, String key) {
        JukeboxSong original = song(key);
        var vanilla = item.getType().getDefaultData(DataComponentTypes.JUKEBOX_PLAYABLE);
        if (original == null || (vanilla != null && vanilla.jukeboxSong().equals(original)))
            item.resetData(DataComponentTypes.JUKEBOX_PLAYABLE);
        else
            item.setData(
                    DataComponentTypes.JUKEBOX_PLAYABLE, JukeboxPlayable.jukeboxPlayable(original));
    }

    private static JukeboxSong song(String name) {
        NamespacedKey key = NamespacedKey.fromString(name);
        return key == null
                ? null
                : RegistryAccess.registryAccess().getRegistry(RegistryKey.JUKEBOX_SONG).get(key);
    }

    static int comparatorOutput(ItemStack item) {
        JukeboxSong song = item.getData(DataComponentTypes.JUKEBOX_PLAYABLE).jukeboxSong();
        try {
            return ((Number) JukeboxSong.class.getMethod("getComparatorOutput").invoke(song))
                    .intValue();
        } catch (NoSuchMethodException olderApi) {
            NamespacedKey key = song.getKey();
            if (key.getNamespace().equals("lazodiscs") && key.getKey().startsWith("recording_")) {
                int value = Integer.parseInt(key.getKey().substring("recording_".length()));
                if (value >= 0 && value <= 15) return value;
            }
            if (key.getNamespace().equals("minecraft"))
                return VanillaDiscs.comparator(key.getKey());
            throw new IllegalArgumentException("Unsupported custom jukebox song: " + key);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read jukebox comparator output", failure);
        }
    }
}
