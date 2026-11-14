package com.eyecrasher.lazodiscs.data;

import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import com.eyecrasher.lazodiscs.voice.SpotifyTitleResolver;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.StringUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;

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
        CompoundTag root = stack.getTag();
        if (root == null) return Optional.empty();
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
            return Optional.of(new CustomDiscData(url, title, range, volume, id));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public static void write(ItemStack stack, CustomDiscData disc) {
        CompoundTag root = stack.getTag() == null ? new CompoundTag() : stack.getTag().copy();
        CompoundTag tag = new CompoundTag();
        tag.putString(URL_KEY, disc.url());
        tag.putString(TITLE_KEY, disc.title());
        tag.putInt(RANGE_KEY, disc.range());
        tag.putFloat(VOLUME_KEY, disc.volume());
        tag.putString(ID_KEY, disc.id().toString());
        root.put(ROOT_KEY, tag);
        stack.setTag(root);
        stack.setHoverName(
                new net.minecraft.network.chat.TextComponent(disc.title())
                        .withStyle(ChatFormatting.AQUA));
        stack.getOrCreateTagElement("display").remove("Lore");
    }

    public static void clear(ItemStack stack) {
        CompoundTag old = stack.getTag();
        if (old == null) return;
        CompoundTag root = old.copy();
        root.remove(ROOT_KEY);
        stack.setTag(root.isEmpty() ? null : root);
        stack.resetHoverName();
        CompoundTag display = stack.getTagElement("display");
        if (display != null) display.remove("Lore");
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
