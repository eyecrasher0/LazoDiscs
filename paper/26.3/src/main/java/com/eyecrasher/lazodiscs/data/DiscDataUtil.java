package com.eyecrasher.lazodiscs.data;

import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import com.eyecrasher.lazodiscs.voice.SpotifyTitleResolver;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/** Recordings live on the item, so drops, inventories, hoppers and world saves retain them. */
public final class DiscDataUtil {
    private static final NamespacedKey ROOT = key("disc");
    private static final NamespacedKey URL = key("url"),
            TITLE = key("title"),
            RANGE = key("range"),
            VOLUME = key("volume"),
            ID = key("id"),
            SONG = key("original_song"),
            NAME = key("original_name");

    private DiscDataUtil() {}

    private static NamespacedKey key(String name) {
        return new NamespacedKey("lazodiscs", name);
    }

    public static boolean isMusicDisc(ItemStack item) {
        return DiscSongs.isMusicDisc(item);
    }

    public static Optional<CustomDiscData> read(ItemStack item) {
        if (!isMusicDisc(item)) return Optional.empty();
        PersistentDataContainer data =
                item.getItemMeta()
                        .getPersistentDataContainer()
                        .get(ROOT, PersistentDataType.TAG_CONTAINER);
        if (data == null) return Optional.empty();
        try {
            String url = data.get(URL, PersistentDataType.STRING);
            String title = data.get(TITLE, PersistentDataType.STRING);
            String id = data.get(ID, PersistentDataType.STRING);
            int range =
                    data.getOrDefault(
                            RANGE, PersistentDataType.INTEGER, LazoDiscsConfig.DEFAULT_RANGE.get());
            float volume = data.getOrDefault(VOLUME, PersistentDataType.FLOAT, 1.0F);
            if (url == null
                    || url.isBlank()
                    || url.length() > 8192
                    || title == null
                    || title.length() > 256
                    || !Float.isFinite(volume)) return Optional.empty();
            return Optional.of(
                    new CustomDiscData(
                            url,
                            title,
                            clampRange(range),
                            Math.max(0, Math.min(volume, 4)),
                            UUID.fromString(id)));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return Optional.empty();
        }
    }

    public static void write(ItemStack item, CustomDiscData disc) {
        if (!isMusicDisc(item)) throw new IllegalArgumentException("Hold a music disc");
        if (item.getAmount() != 1) throw new IllegalArgumentException("Hold a single music disc");
        if (!Float.isFinite(disc.volume())) throw new IllegalArgumentException("Invalid volume");
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        PersistentDataContainer data = pdc.get(ROOT, PersistentDataType.TAG_CONTAINER);
        String original = DiscSongs.songKey(item);
        int comparator = comparatorOutput(item);
        if (data == null) {
            data = pdc.getAdapterContext().newPersistentDataContainer();
            if (original != null) data.set(SONG, PersistentDataType.STRING, original);
            Component name = meta.displayName();
            if (name != null)
                data.set(
                        NAME,
                        PersistentDataType.STRING,
                        GsonComponentSerializer.gson().serialize(name));
        }
        data.set(URL, PersistentDataType.STRING, validateUrl(disc.url()));
        data.set(TITLE, PersistentDataType.STRING, sanitizeTitle(disc.title()));
        data.set(RANGE, PersistentDataType.INTEGER, clampRange(disc.range()));
        data.set(VOLUME, PersistentDataType.FLOAT, Math.max(0, Math.min(disc.volume(), 4)));
        data.set(ID, PersistentDataType.STRING, disc.id().toString());
        pdc.set(ROOT, PersistentDataType.TAG_CONTAINER, data);
        meta.displayName(Component.text(sanitizeTitle(disc.title()), NamedTextColor.AQUA));
        item.setItemMeta(meta);
        DiscSongs.record(item, comparator);
    }

    public static void clear(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer data =
                meta.getPersistentDataContainer().get(ROOT, PersistentDataType.TAG_CONTAINER);
        if (data == null) return;
        String originalName = data.get(NAME, PersistentDataType.STRING);
        meta.displayName(
                originalName == null
                        ? null
                        : GsonComponentSerializer.gson().deserialize(originalName));
        meta.getPersistentDataContainer().remove(ROOT);
        item.setItemMeta(meta);
        DiscSongs.restore(item, data.getOrDefault(SONG, PersistentDataType.STRING, ""));
    }

    public static int comparatorOutput(ItemStack item) {
        return DiscSongs.comparatorOutput(item);
    }

    public static String validateUrl(String raw) {
        if (raw == null || raw.isBlank())
            throw new IllegalArgumentException(LazoDiscsText.urlEmpty());
        String url = raw.trim();
        if (url.length() > 8192) throw new IllegalArgumentException(LazoDiscsText.urlInvalid());
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(LazoDiscsText.urlInvalid());
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("spotify")) {
            SpotifyTitleResolver.validateSingleTrack(url)
                    .ifPresent(
                            error -> {
                                throw new IllegalArgumentException(error);
                            });
            return validateUrl(SpotifyTitleResolver.canonicalize(url));
        }
        if (!scheme.equals("http") && !scheme.equals("https"))
            throw new IllegalArgumentException(LazoDiscsText.unsupportedScheme());
        if (scheme.equals("http") && !LazoDiscsConfig.ALLOW_HTTP.get())
            throw new IllegalArgumentException(LazoDiscsText.httpDisabled());
        if (scheme.equals("https") && !LazoDiscsConfig.ALLOW_HTTPS.get())
            throw new IllegalArgumentException(LazoDiscsText.httpsDisabled());
        if (uri.getHost() == null || uri.getUserInfo() != null)
            throw new IllegalArgumentException(LazoDiscsText.urlInvalid());
        boolean spotify = SpotifyTitleResolver.looksLikeSpotify(url);
        if (spotify)
            SpotifyTitleResolver.validateSingleTrack(url)
                    .ifPresent(
                            error -> {
                                throw new IllegalArgumentException(error);
                            });
        var domains = LazoDiscsConfig.ALLOWED_DOMAINS.get();
        if (!domains.isEmpty()) {
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (domains.stream()
                    .map(s -> s.toLowerCase(Locale.ROOT).trim())
                    .filter(s -> !s.isEmpty())
                    .noneMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed))) {
                throw new IllegalArgumentException(LazoDiscsText.domainNotAllowed());
            }
        }
        return spotify ? SpotifyTitleResolver.canonicalize(url) : url;
    }

    public static int clampRange(int range) {
        return Math.max(1, Math.min(range, LazoDiscsConfig.MAX_RANGE.get()));
    }

    public static String sanitizeTitle(String title) {
        if (title == null || title.isBlank()) return LazoDiscsText.unknown();
        String clean = title.replaceAll("[\\p{Cntrl}]", " ").trim();
        return clean.substring(0, Math.min(256, clean.length()));
    }
}
