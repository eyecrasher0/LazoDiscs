package com.eyecrasher.lazodiscs.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.List;

/** Paper adapter for the options used by the shared audio engine. */
public final class LazoDiscsConfig {
    public static final Value<Integer> DEFAULT_RANGE = new Value<>(64);
    public static final Value<Integer> MAX_RANGE = new Value<>(128);
    public static final Value<Double> DEFAULT_VOLUME = new Value<>(1.0);
    public static final Value<Double> SOURCE_LINE_DEFAULT_VOLUME = new Value<>(1.0);
    public static final Value<String> LANGUAGE = new Value<>("en_us");
    public static final Value<String> COMMAND_ALIAS = new Value<>("lazodisc");
    public static final Value<Boolean> ALLOW_HTTP = new Value<>(false);
    public static final Value<Boolean> ALLOW_HTTPS = new Value<>(true);
    public static final Value<List<String>> ALLOWED_DOMAINS = new Value<>(List.of());
    public static final Value<Integer> LAVAPLAYER_LOAD_TIMEOUT_SECONDS = new Value<>(60);
    public static final Value<Integer> VALIDATION_INTERVAL_TICKS = new Value<>(20);
    public static final Value<Boolean> SPOTIFY_SEARCH_VIA_YOUTUBE = new Value<>(true);
    public static final Value<Integer> MAX_STREAMING_TRACK_LENGTH_SECONDS = new Value<>(0);
    public static final Value<Integer> MAX_CONCURRENT_AUDIO_LOADS = new Value<>(3);
    private static Path directory;

    private LazoDiscsConfig() {}

    public static void load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        directory = plugin.getDataFolder().toPath();
        FileConfiguration c = plugin.getConfig();
        DEFAULT_RANGE.value = bounded(c.getInt("playback.defaultRange", 64), 1, 512);
        MAX_RANGE.value = bounded(c.getInt("playback.maxRange", 128), 1, 1024);
        DEFAULT_VOLUME.value = bounded(c.getDouble("playback.defaultVolume", 1), 0, 4);
        SOURCE_LINE_DEFAULT_VOLUME.value =
                bounded(c.getDouble("playback.sourceLineDefaultVolume", 1), 0, 1);
        LANGUAGE.value = c.getString("language", "en_us");
        String alias = c.getString("commandAlias", "lazodisc").trim();
        if (!alias.matches("[a-z][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("commandAlias must be a lowercase command name");
        }
        COMMAND_ALIAS.value = alias;
        ALLOW_HTTP.value = c.getBoolean("security.allowHttp", false);
        ALLOW_HTTPS.value = c.getBoolean("security.allowHttps", true);
        ALLOWED_DOMAINS.value = List.copyOf(c.getStringList("security.allowedDomains"));
        LAVAPLAYER_LOAD_TIMEOUT_SECONDS.value =
                bounded(c.getInt("lavaplayer.loadTimeoutSeconds", 60), 5, 300);
        VALIDATION_INTERVAL_TICKS.value =
                bounded(c.getInt("playback.validationIntervalTicks", 20), 1, 200);
        SPOTIFY_SEARCH_VIA_YOUTUBE.value = c.getBoolean("lavaplayer.spotifySearchViaYoutube", true);
        MAX_STREAMING_TRACK_LENGTH_SECONDS.value =
                bounded(c.getInt("lavaplayer.maxStreamingTrackLengthSeconds", 0), 0, 86400);
        MAX_CONCURRENT_AUDIO_LOADS.value =
                bounded(c.getInt("lavaplayer.maxConcurrentAudioLoads", 3), 1, 32);
    }

    public static Path configDir() {
        return directory;
    }

    private static int bounded(int n, int min, int max) {
        return Math.max(min, Math.min(n, max));
    }

    private static double bounded(double n, double min, double max) {
        if (!Double.isFinite(n))
            throw new IllegalArgumentException("Playback volume must be finite");
        return Math.max(min, Math.min(n, max));
    }

    public static final class Value<T> {
        private volatile T value;

        private Value(T value) {
            this.value = value;
        }

        public T get() {
            return value;
        }
    }
}
