package com.eyecrasher.lazodiscs.data;

import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import com.eyecrasher.lazodiscs.voice.SpotifyTitleResolver;

import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.TooltipDisplay;

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
        return !stack.isEmpty() && stack.get(DataComponents.JUKEBOX_PLAYABLE) != null;
    }

    public static boolean hasCustomDisc(ItemStack stack) {
        return read(stack).isPresent();
    }

    public static Optional<CustomDiscData> read(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return Optional.empty();
        CompoundTag root = data.copyTag();
        if (!root.contains(ROOT_KEY)) return Optional.empty();

        Optional<CompoundTag> tagOptional = root.getCompound(ROOT_KEY);
        if (tagOptional.isEmpty()) return Optional.empty();
        CompoundTag tag = tagOptional.get();

        Optional<String> urlOptional = tag.getString(URL_KEY);
        Optional<String> idOptional = tag.getString(ID_KEY);
        if (urlOptional.isEmpty() || idOptional.isEmpty()) return Optional.empty();

        try {
            String url = urlOptional.get();
            String title = tag.getString(TITLE_KEY).orElse(url);
            int range = tag.getInt(RANGE_KEY).orElse(LazoDiscsConfig.DEFAULT_RANGE.get());
            float volume =
                    tag.getFloat(VOLUME_KEY)
                            .orElse(LazoDiscsConfig.DEFAULT_VOLUME.get().floatValue());
            UUID id = UUID.fromString(idOptional.get());
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
        hideVanillaDiscTooltip(stack);
        // Remove custom lore. The old extra-tooltip hide component was removed in newer Minecraft.
        stack.remove(DataComponents.LORE);
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
        showVanillaDiscTooltip(stack);
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

    private static void hideVanillaDiscTooltip(ItemStack stack) {
        TooltipDisplay tooltip =
                stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT);
        stack.set(
                DataComponents.TOOLTIP_DISPLAY,
                tooltip.withHidden(DataComponents.JUKEBOX_PLAYABLE, true));
    }

    private static void showVanillaDiscTooltip(ItemStack stack) {
        TooltipDisplay tooltip = stack.get(DataComponents.TOOLTIP_DISPLAY);
        if (tooltip == null) return;

        TooltipDisplay visible = tooltip.withHidden(DataComponents.JUKEBOX_PLAYABLE, false);
        if (visible.equals(TooltipDisplay.DEFAULT)) {
            stack.remove(DataComponents.TOOLTIP_DISPLAY);
        } else {
            stack.set(DataComponents.TOOLTIP_DISPLAY, visible);
        }
    }
}
