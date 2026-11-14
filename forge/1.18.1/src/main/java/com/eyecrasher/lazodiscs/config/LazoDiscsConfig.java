package com.eyecrasher.lazodiscs.config;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

public final class LazoDiscsConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue DEFAULT_RANGE;
    public static final ForgeConfigSpec.IntValue MAX_RANGE;
    public static final ForgeConfigSpec.DoubleValue DEFAULT_VOLUME;
    public static final ForgeConfigSpec.DoubleValue SOURCE_LINE_DEFAULT_VOLUME;
    public static final ForgeConfigSpec.ConfigValue<String> LANGUAGE;
    public static final ForgeConfigSpec.ConfigValue<String> COMMAND_ALIAS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_PLAYBACK_ON_SABLE_PLATFORMS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_HTTP;
    public static final ForgeConfigSpec.BooleanValue ALLOW_HTTPS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> ALLOWED_DOMAINS;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_PERMISSION_FOR_BURN_COMMAND;
    public static final ForgeConfigSpec.IntValue BURN_PERMISSION_LEVEL;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_PERMISSION_FOR_ERASE_COMMAND;
    public static final ForgeConfigSpec.IntValue ERASE_PERMISSION_LEVEL;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_PERMISSION_FOR_SEARCH_COMMAND;
    public static final ForgeConfigSpec.IntValue SEARCH_PERMISSION_LEVEL;
    public static final ForgeConfigSpec.BooleanValue REQUIRE_PERMISSION_FOR_PLAY;
    public static final ForgeConfigSpec.IntValue PLAY_PERMISSION_LEVEL;
    public static final ForgeConfigSpec.IntValue LAVAPLAYER_LOAD_TIMEOUT_SECONDS;
    public static final ForgeConfigSpec.IntValue VALIDATION_INTERVAL_TICKS;
    public static final ForgeConfigSpec.BooleanValue SPOTIFY_SEARCH_VIA_YOUTUBE;
    public static final ForgeConfigSpec.IntValue MAX_STREAMING_TRACK_LENGTH_SECONDS;
    public static final ForgeConfigSpec.IntValue MAX_CONCURRENT_AUDIO_LOADS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        LANGUAGE =
                builder.comment(
                                "Server message language. Loads from"
                                    + " config/lazodiscs/lang/<language>.toml. Built-in: en_us.")
                        .define("language", "en_us");

        COMMAND_ALIAS =
                builder.comment(
                                "Root command name. /lazodisc by default. Change to /disc, /music"
                                        + " etc. Requires server restart.")
                        .define("commandAlias", "lazodisc");

        builder.push("playback");
        DEFAULT_RANGE =
                builder.comment(
                                "Default hearing radius (blocks) for burned discs. Written to disc"
                                        + " NBT on /lazodisc burn.")
                        .defineInRange("defaultRange", 64, 1, 512);
        MAX_RANGE =
                builder.comment(
                                "Maximum hearing radius a disc can have. /lazodisc burn clamps to"
                                        + " this.")
                        .defineInRange("maxRange", 128, 1, 1024);
        DEFAULT_VOLUME =
                builder.comment(
                                "Default volume multiplier for burned discs (0.0 = silent, 4.0 ="
                                        + " very loud).")
                        .defineInRange("defaultVolume", 1.0D, 0.0D, 4.0D);
        SOURCE_LINE_DEFAULT_VOLUME =
                builder.comment(
                                "Default client volume for the Plasmo Voice source line 'Discs'"
                                    + " (0.0-1.0). Players can adjust in Plasmo Voice settings.")
                        .defineInRange("sourceLineDefaultVolume", 1.0D, 0.0D, 1.0D);
        VALIDATION_INTERVAL_TICKS =
                builder.comment(
                                "How often (in ticks) to re-validate active jukeboxes. 1 = every"
                                    + " tick, 20 = once per second. Lower = more responsive, higher"
                                    + " = less CPU.")
                        .defineInRange("validationIntervalTicks", 20, 1, 200);
        ALLOW_PLAYBACK_ON_SABLE_PLATFORMS =
                builder.comment(
                                "EXPERIMENTAL: Allow playback from jukeboxes on Sable moving"
                                    + " platforms (Create Aeronautics). When false, jukeboxes on"
                                    + " moving platforms refuse to play. When true, position is"
                                    + " projected to real-world coordinates and updated every"
                                    + " physics tick (~60 Hz).")
                        .define("playback.allowPlaybackOnSablePlatforms", false);
        builder.pop();

        builder.push("lavaplayer");
        LAVAPLAYER_LOAD_TIMEOUT_SECONDS =
                builder.comment(
                                "Timeout for loading/resolving audio URLs (YouTube, SoundCloud,"
                                        + " etc.) in seconds.")
                        .defineInRange("loadTimeoutSeconds", 60, 5, 300);
        SPOTIFY_SEARCH_VIA_YOUTUBE =
                builder.comment(
                                "Resolve Spotify track links by searching YouTube Music for a"
                                        + " matching track. Spotify doesn't provide direct audio"
                                        + " streams, so metadata is scraped and matched.")
                        .define("spotifySearchViaYoutube", true);
        MAX_STREAMING_TRACK_LENGTH_SECONDS =
                builder.comment(
                                "Maximum streaming track length in seconds. 0 = unlimited. Prevents"
                                        + " burning very long streams onto discs.")
                        .defineInRange("maxStreamingTrackLengthSeconds", 0, 0, 24 * 60 * 60);
        MAX_CONCURRENT_AUDIO_LOADS =
                builder.comment(
                                "Maximum concurrent audio load operations (thread pool size for"
                                        + " /lazodisc burn and /lazodisc search).")
                        .defineInRange("maxConcurrentAudioLoads", 3, 1, 32);
        builder.pop();

        builder.push("security");
        ALLOW_HTTP =
                builder.comment("Allow http:// URLs (not recommended — use HTTPS for security).")
                        .define("allowHttp", false);
        ALLOW_HTTPS = builder.comment("Allow https:// URLs.").define("allowHttps", true);
        ALLOWED_DOMAINS =
                builder.comment(
                                "Domain allowlist. Empty = any domain allowed. Example:"
                                        + " [\"youtube.com\", \"open.spotify.com\","
                                        + " \"soundcloud.com\"]")
                        .defineList("allowedDomains", List.of(), o -> o instanceof String);
        REQUIRE_PERMISSION_FOR_BURN_COMMAND =
                builder.comment("Require permission to use /lazodisc burn.")
                        .define("requirePermissionForBurnCommand", true);
        BURN_PERMISSION_LEVEL =
                builder.comment(
                                "Permission level for /lazodisc burn. 0 = anyone, 2 = admin"
                                        + " (default), 4 = owner.")
                        .defineInRange("burnPermissionLevel", 2, 0, 4);
        REQUIRE_PERMISSION_FOR_ERASE_COMMAND =
                builder.comment("Require permission to use /lazodisc erase.")
                        .define("requirePermissionForEraseCommand", true);
        ERASE_PERMISSION_LEVEL =
                builder.comment("Permission level for /lazodisc erase.")
                        .defineInRange("erasePermissionLevel", 2, 0, 4);
        REQUIRE_PERMISSION_FOR_SEARCH_COMMAND =
                builder.comment("Require permission to use /lazodisc search.")
                        .define("requirePermissionForSearchCommand", true);
        SEARCH_PERMISSION_LEVEL =
                builder.comment("Permission level for /lazodisc search.")
                        .defineInRange("searchPermissionLevel", 2, 0, 4);
        REQUIRE_PERMISSION_FOR_PLAY =
                builder.comment(
                                "Require permission to PLAY (insert into jukebox) already-burned"
                                    + " discs. false = any player can play (default). true = only"
                                    + " operators.")
                        .define("requirePermissionForPlay", false);
        PLAY_PERMISSION_LEVEL =
                builder.comment("Permission level for playing discs.")
                        .defineInRange("playPermissionLevel", 2, 0, 4);
        builder.pop();

        SPEC = builder.build();
    }

    private LazoDiscsConfig() {}
}
