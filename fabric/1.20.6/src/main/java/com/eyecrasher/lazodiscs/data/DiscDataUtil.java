package com.eyecrasher.lazodiscs.data;

import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import com.eyecrasher.lazodiscs.voice.SpotifyTitleResolver;

import net.minecraft.ChatFormatting;
import net.minecraft.world.item.RecordItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

public final class DiscDataUtil {
    public static final String ROOT_KEY = "lazodiscs";
    private static final String URL_KEY = "url";
    private static final String TITLE_KEY = "title";
    private static final String RANGE_KEY = "range";
    private static final String VOLUME_KEY = "volume";
    private static final String ID_KEY = "id";

    private DiscDataUtil() {}

    public static boolean isMusicDisc(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof RecordItem;
    }

    public static boolean hasCustomDisc(ItemStack stack) {
        return read(stack).isPresent();
    }

    public static Optional<CustomDiscData> read(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return Optional.empty();
        CompoundTag root = data.copyTag();
        if (!root.contains(ROOT_KEY, 10)) return Optional.empty();

        CompoundTag tag = root.getCompound(ROOT_KEY);
        if (!tag.contains(URL_KEY, 8) || !tag.contains(ID_KEY, 8)) return Optional.empty();

        try {
            String url = tag.getString(URL_KEY);
            String title = tag.contains(TITLE_KEY, 8) ? tag.getString(TITLE_KEY) : url;
            int range =
                    tag.contains(RANGE_KEY, 3)
                            ? tag.getInt(RANGE_KEY)
                            : LazoDiscsConfig.DEFAULT_RANGE.get();
            float volume =
                    tag.contains(VOLUME_KEY, 5)
                            ? tag.getFloat(VOLUME_KEY)
                            : LazoDiscsConfig.DEFAULT_VOLUME.get().floatValue();
            UUID id = UUID.fromString(tag.getString(ID_KEY));
            return Optional.of(new CustomDiscData(url, title, clampRange(range), volume, id));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public static void write(ItemStack stack, CustomDiscData disc) {
        CompoundTag root =
                stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        CompoundTag tag = new CompoundTag();
        tag.putString(URL_KEY, disc.url());
        tag.putString(TITLE_KEY, disc.title());
        tag.putInt(RANGE_KEY, disc.range());
        tag.putFloat(VOLUME_KEY, disc.volume());
        tag.putString(ID_KEY, disc.id().toString());
        root.put(ROOT_KEY, tag);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        stack.set(
                DataComponents.CUSTOM_NAME,
                Component.literal(disc.title()).withStyle(ChatFormatting.AQUA));
        // Remove/disable original vanilla music disc tooltip/lore, e.g. "C418 - Cat".
        // In 1.21.1 the original song line is usually produced by the JUKEBOX_PLAYABLE component,
        // not by the LORE component, so we hide additional tooltip too.
        stack.remove(DataComponents.LORE);
        stack.set(DataComponents.HIDE_ADDITIONAL_TOOLTIP, Unit.INSTANCE);
    }

    public static void clear(ItemStack stack) {
        CustomData old = stack.get(DataComponents.CUSTOM_DATA);
        if (old == null) return;
        CompoundTag root = old.copyTag();
        root.remove(ROOT_KEY);
        if (root.isEmpty()) {
            stack.remove(DataComponents.CUSTOM_DATA);
        } else {
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        }
        stack.remove(DataComponents.CUSTOM_NAME);
        stack.remove(DataComponents.LORE);
        stack.remove(DataComponents.HIDE_ADDITIONAL_TOOLTIP);
    }

    public static String validateUrl(String raw) throws IllegalArgumentException {
        if (StringUtil.isNullOrEmpty(raw))
            throw new IllegalArgumentException(LazoDiscsText.urlEmpty());
        String trimmed = raw.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (Exception e) {
            throw new IllegalArgumentException(LazoDiscsText.urlInvalid());
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("spotify")) {
            Optional<String> spotifyError = SpotifyTitleResolver.validateSingleTrack(trimmed);
            if (spotifyError.isPresent()) throw new IllegalArgumentException(spotifyError.get());
            // Spotify URIs become HTTPS URLs and must obey the same server policy.
            return validateUrl(SpotifyTitleResolver.canonicalize(trimmed));
        }
        if (scheme.equals("http") && !LazoDiscsConfig.ALLOW_HTTP.get())
            throw new IllegalArgumentException(LazoDiscsText.httpDisabled());
        if (scheme.equals("https") && !LazoDiscsConfig.ALLOW_HTTPS.get())
            throw new IllegalArgumentException(LazoDiscsText.httpsDisabled());
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException(LazoDiscsText.unsupportedScheme());
        }

        if (uri.getHost() == null) {
            throw new IllegalArgumentException(LazoDiscsText.urlInvalid());
        }

        if (SpotifyTitleResolver.looksLikeSpotify(trimmed)) {
            Optional<String> spotifyError = SpotifyTitleResolver.validateSingleTrack(trimmed);
            if (spotifyError.isPresent()) throw new IllegalArgumentException(spotifyError.get());
        }

        var domains = LazoDiscsConfig.ALLOWED_DOMAINS.get();
        if (!domains.isEmpty()) {
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            boolean ok =
                    domains.stream()
                            .map(s -> s.toLowerCase(Locale.ROOT).trim())
                            .anyMatch(
                                    allowed ->
                                            host.equals(allowed) || host.endsWith("." + allowed));
            if (!ok) throw new IllegalArgumentException(LazoDiscsText.domainNotAllowed());
        }
        return SpotifyTitleResolver.looksLikeSpotify(trimmed)
                ? SpotifyTitleResolver.canonicalize(trimmed)
                : trimmed;
    }

    public static int clampRange(int range) {
        return Math.max(1, Math.min(range, LazoDiscsConfig.MAX_RANGE.get()));
    }
}
