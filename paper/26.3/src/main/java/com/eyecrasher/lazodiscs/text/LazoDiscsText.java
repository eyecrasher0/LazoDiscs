package com.eyecrasher.lazodiscs.text;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;

import net.kyori.adventure.text.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class LazoDiscsText {
    private static final String DEFAULT_LANGUAGE = "en_us";
    private static final String RESOURCE_PATH = "lazodiscs_languages/en_us.toml";
    private static final Object LOCK = new Object();
    private static final Map<String, String> DEFAULTS = defaultTranslations();

    private static volatile Map<String, String> translations = DEFAULTS;
    private static volatile String loadedLanguage = "";
    private static volatile long loadedModifiedAt = Long.MIN_VALUE;

    private LazoDiscsText() {}

    public static void reload() {
        load(true);
    }

    public static String sourceLineName() {
        return text("source_line.discs");
    }

    public static String nowPlaying(String title) {
        return text("now_playing", "title", title);
    }

    public static Component holdDisc() {
        return component("command.hold_disc");
    }

    public static Component invalidUrl(String reason) {
        return component("command.invalid_url", "reason", reason);
    }

    public static Component burnUsage() {
        return component("command.burn_usage");
    }

    public static Component burned(String title) {
        return component("command.burned", "title", title);
    }

    public static Component resolvingTrack() {
        return component("command.resolving_track");
    }

    public static Component burnFailed(String reason) {
        return component("command.burn_failed", "reason", clean(reason));
    }

    public static Component audioLoadFailed(String title, String reason) {
        return component("audio.load_failed", "title", title, "reason", clean(reason));
    }

    public static String audioLoadBusy() {
        return text("audio.load_busy");
    }

    public static Component notLazoDisc() {
        return component("command.not_lazodisc");
    }

    public static Component dataRemoved() {
        return component("command.data_removed");
    }

    public static Component plasmoVoiceRequired() {
        return component("dependency.plasmo_voice_required");
    }

    public static Component noPermission() {
        return component("permission.no_permission");
    }

    public static Component playNoPermission() {
        return component("permission.play_no_permission");
    }

    public static Component playersOnly() {
        return component("command.players_only");
    }

    public static Component searchUsage() {
        return component("search.usage");
    }

    public static Component searchNamesOnly() {
        return component("search.names_only");
    }

    public static Component searching(String query) {
        return component("search.searching", "query", query);
    }

    public static Component searchFailed(String reason) {
        return component("search.failed", "reason", clean(reason));
    }

    public static Component noSongsFound(String query) {
        return component("search.no_songs", "query", query);
    }

    public static Component searchHeader(String query) {
        return component("search.header", "query", query);
    }

    public static String clickToPaste(String command) {
        return text("search.click_to_paste", "command", command);
    }

    public static String unknown() {
        return text("common.unknown");
    }

    public static String urlEmpty() {
        return text("url.empty");
    }

    public static String urlInvalid() {
        return text("url.invalid");
    }

    public static String httpDisabled() {
        return text("url.http_disabled");
    }

    public static String httpsDisabled() {
        return text("url.https_disabled");
    }

    public static String unsupportedScheme() {
        return text("url.unsupported_scheme");
    }

    public static String domainNotAllowed() {
        return text("url.domain_not_allowed");
    }

    public static String spotifyTrackOnly() {
        return text("spotify.track_only");
    }

    public static String spotifyInvalidTrack() {
        return text("spotify.invalid_track");
    }

    public static String spotifyDisabled() {
        return text("spotify.disabled");
    }

    public static String spotifyMetadataFailed() {
        return text("spotify.metadata_failed");
    }

    public static String audioNoMatches() {
        return text("audio.no_matches");
    }

    public static String audioResolveTimedOut(int seconds) {
        return text("audio.resolve_timed_out", "seconds", seconds);
    }

    public static String searchTimedOut(int seconds) {
        return text("search.timed_out", "seconds", seconds);
    }

    public static String audioDecodedZeroSamples() {
        return text("audio.zero_samples");
    }

    public static String audioNoFrames() {
        return text("audio.no_frames");
    }

    public static String trackStuck(long thresholdMs) {
        return text("audio.track_stuck", "threshold_ms", thresholdMs);
    }

    public static String trackTooLong(long maxSeconds) {
        return text("audio.track_too_long", "max", formatSeconds(maxSeconds));
    }

    private static Component component(String key, Object... replacements) {
        return Component.text(text(key, replacements));
    }

    private static String text(String key, Object... replacements) {
        ensureLoaded();
        String value = translations.getOrDefault(key, DEFAULTS.getOrDefault(key, key));
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            value = value.replace("%" + replacements[i] + "%", clean(replacements[i + 1]));
        }
        return value;
    }

    private static String clean(Object value) {
        if (value == null) return unknown();
        String text = String.valueOf(value);
        return text.isBlank() ? unknown() : text;
    }

    private static void ensureLoaded() {
        load(false);
    }

    private static void load(boolean force) {
        String language = configuredLanguage();
        Path file = languageFile(language);
        long modifiedAt = modifiedAt(file);
        if (!force && language.equals(loadedLanguage) && modifiedAt == loadedModifiedAt) {
            return;
        }

        synchronized (LOCK) {
            modifiedAt = modifiedAt(file);
            if (!force && language.equals(loadedLanguage) && modifiedAt == loadedModifiedAt) {
                return;
            }

            ensureLanguageFiles(language);
            file = languageFile(language);
            modifiedAt = modifiedAt(file);

            Map<String, String> loaded = new LinkedHashMap<>(DEFAULTS);
            if (Files.isRegularFile(file)) {
                loaded.putAll(readTomlStrings(file));
            } else if (!DEFAULT_LANGUAGE.equals(language)) {
                Path fallback = languageFile(DEFAULT_LANGUAGE);
                loaded.putAll(readTomlStrings(fallback));
            }

            translations = Map.copyOf(loaded);
            loadedLanguage = language;
            loadedModifiedAt = modifiedAt;
            LazoDiscs.LOGGER.info("Loaded LazoDiscs language '{}'", language);
        }
    }

    private static void ensureLanguageFiles(String selectedLanguage) {
        try {
            Path dir = langDir();
            Files.createDirectories(dir);
            writeDefaultIfMissing(dir.resolve(DEFAULT_LANGUAGE + ".toml"));
            if (!DEFAULT_LANGUAGE.equals(selectedLanguage)) {
                writeDefaultIfMissing(dir.resolve(selectedLanguage + ".toml"));
            }
        } catch (Exception e) {
            LazoDiscs.LOGGER.warn("Could not prepare LazoDiscs language files: {}", e.toString());
        }
    }

    private static void writeDefaultIfMissing(Path file) throws Exception {
        if (Files.exists(file)) return;
        try (InputStream input =
                LazoDiscsText.class.getClassLoader().getResourceAsStream(RESOURCE_PATH)) {
            if (input != null) {
                Files.copy(input, file);
                return;
            }
        }

        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writeDefaultTranslations(writer);
        }
    }

    private static Map<String, String> readTomlStrings(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("["))
                    continue;
                int eq = trimmed.indexOf('=');
                if (eq <= 0) continue;
                String key = trimmed.substring(0, eq).trim();
                String value = parseTomlValue(trimmed.substring(eq + 1).trim());
                if (!key.isBlank()) out.put(key, value);
            }
        } catch (Exception e) {
            LazoDiscs.LOGGER.warn(
                    "Could not read LazoDiscs language file '{}': {}", file, e.toString());
        }
        return out;
    }

    private static String parseTomlValue(String value) {
        if (value.startsWith("\"")) {
            int end = findClosingQuote(value);
            if (end > 0) return unescape(value.substring(1, end));
        }
        int comment = value.indexOf('#');
        String raw = comment >= 0 ? value.substring(0, comment) : value;
        return raw.trim();
    }

    private static int findClosingQuote(String value) {
        boolean escaped = false;
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean escaped = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!escaped) {
                if (c == '\\') escaped = true;
                else out.append(c);
                continue;
            }

            switch (c) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                default -> out.append(c);
            }
            escaped = false;
        }
        if (escaped) out.append('\\');
        return out.toString();
    }

    private static String configuredLanguage() {
        try {
            String value = LazoDiscsConfig.LANGUAGE.get();
            if (value == null || value.isBlank()) return DEFAULT_LANGUAGE;
            String language = value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            return language.matches("[\\p{L}\\p{Nd}_]+") ? language : DEFAULT_LANGUAGE;
        } catch (RuntimeException ignored) {
            return DEFAULT_LANGUAGE;
        }
    }

    private static Path languageFile(String language) {
        return langDir().resolve(language + ".toml");
    }

    private static Path langDir() {
        return LazoDiscsConfig.configDir().resolve("lang");
    }

    private static long modifiedAt(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : -1L;
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private static Map<String, String> defaultTranslations() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("source_line.discs", "Discs");
        map.put("now_playing", "Now playing: %title%");
        map.put("common.unknown", "Unknown");
        map.put("command.hold_disc", "Hold a vanilla music disc in your main hand.");
        map.put("command.invalid_url", "Invalid URL: %reason%");
        map.put("command.burn_usage", "Usage: /lazodisc burn <url> [title]");
        map.put("command.burned", "Burned LazoDisc: %title%");
        map.put("command.resolving_track", "Checking track...");
        map.put("command.burn_failed", "Could not burn disc: %reason%");
        map.put("audio.load_busy", "Too many audio requests. Try again shortly.");
        map.put("command.players_only", "Only players can use this command.");
        map.put("command.not_lazodisc", "This item is not a LazoDisc.");
        map.put("command.data_removed", "LazoDisc data removed.");
        map.put("dependency.plasmo_voice_required", "Plasmo Voice is required to play LazoDiscs.");
        map.put("permission.no_permission", "You do not have permission to use LazoDiscs.");
        map.put("permission.play_no_permission", "You do not have permission to play LazoDiscs.");
        map.put("search.usage", "Usage: /lazodisc search <song name>");
        map.put(
                "search.names_only",
                "Use /lazodisc search with a song name. Use /lazodisc burn for links.");
        map.put("search.searching", "Searching: %query%");
        map.put("search.failed", "Search failed: %reason%");
        map.put("search.no_songs", "No songs found for: %query%");
        map.put("search.header", "=== LazoDiscs Search: %query% ===");
        map.put("search.click_to_paste", "Click to paste: %command%");
        map.put("search.timed_out", "Search timed out after %seconds% seconds");
        map.put("url.empty", "URL is empty");
        map.put("url.invalid", "Invalid URL");
        map.put("url.http_disabled", "HTTP URLs are disabled");
        map.put("url.https_disabled", "HTTPS URLs are disabled");
        map.put("url.unsupported_scheme", "Only HTTP/HTTPS URLs or spotify: URIs are supported");
        map.put("url.domain_not_allowed", "Domain is not allowed by config");
        map.put(
                "spotify.track_only",
                "Only Spotify track links are supported for LazoDiscs. Search album/playlist tracks"
                        + " by song name.");
        map.put("spotify.invalid_track", "Invalid Spotify track link");
        map.put("spotify.disabled", "Spotify search is disabled in config");
        map.put("spotify.metadata_failed", "Could not read Spotify track metadata");
        map.put("audio.load_failed", "%reason%");
        map.put("audio.no_matches", "No matching audio was found");
        map.put("audio.resolve_timed_out", "Audio resolve timed out after %seconds% seconds");
        map.put("audio.zero_samples", "Decoded audio contains no samples");
        map.put("audio.no_frames", "No audio frames were received from LavaPlayer for 15 seconds");
        map.put("audio.track_stuck", "Track got stuck for %threshold_ms% ms");
        map.put("audio.track_too_long", "Track is too long. Max: %max%");
        return map;
    }

    private static void writeDefaultTranslations(BufferedWriter writer) throws Exception {
        writer.write("# LazoDiscs server messages");
        writer.newLine();
        writer.write("# Placeholders use %name%, for example %title% or %reason%.");
        writer.newLine();
        writer.newLine();
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            writer.write(entry.getKey());
            writer.write(" = \"");
            writer.write(escape(entry.getValue()));
            writer.write("\"");
            writer.newLine();
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String formatSeconds(long seconds) {
        long safeSeconds = Math.max(0L, seconds);
        long hours = safeSeconds / 3600L;
        long minutes = (safeSeconds % 3600L) / 60L;
        long remainingSeconds = safeSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remainingSeconds);
        }
        return String.format(Locale.ROOT, "%d:%02d", minutes, remainingSeconds);
    }
}
