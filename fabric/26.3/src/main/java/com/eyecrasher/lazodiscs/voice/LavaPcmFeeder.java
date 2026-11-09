package com.eyecrasher.lazodiscs.voice;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;
import com.eyecrasher.lazodiscs.data.DiscDataUtil;
import com.eyecrasher.lazodiscs.service.TrackMatchScorer;
import com.eyecrasher.lazodiscs.service.TrackMetadata;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.beam.BeamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.getyarn.GetyarnAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.nico.NicoAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.yamusic.YandexMusicAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.AndroidMusicWithThumbnail;
import dev.lavalink.youtube.clients.AndroidVrWithThumbnail;
import dev.lavalink.youtube.clients.AndroidWithThumbnail;
import dev.lavalink.youtube.clients.IosWithThumbnail;
import dev.lavalink.youtube.clients.MWebWithThumbnail;
import dev.lavalink.youtube.clients.MusicWithThumbnail;
import dev.lavalink.youtube.clients.WebEmbeddedWithThumbnail;
import dev.lavalink.youtube.clients.WebWithThumbnail;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LavaPlayer resolver/player path. Tracks are streamed into Plasmo Voice instead of being decoded
 * into a RAM cache before playback.
 */
public final class LavaPcmFeeder {
    private static final AudioPlayerManager PLAYER_MANAGER =
            createPlayerManager(StandardAudioDataFormats.DISCORD_OPUS, "streaming");

    private LavaPcmFeeder() {}

    /** Releases loader/decoder threads when the Paper plugin is disabled. */
    public static void shutdown() {
        PLAYER_MANAGER.shutdown();
    }

    private static AudioPlayerManager createPlayerManager(
            AudioDataFormat outputFormat, String label) {
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        manager.getConfiguration().setOutputFormat(outputFormat);
        manager.setFrameBufferDuration(500);
        manager.setPlayerCleanupThreshold(30_000L);

        try {
            manager.registerSourceManager(
                    new YoutubeAudioSourceManager(
                            true,
                            new MusicWithThumbnail(),
                            new MWebWithThumbnail(),
                            new AndroidMusicWithThumbnail(),
                            new AndroidWithThumbnail(),
                            new IosWithThumbnail(),
                            new WebWithThumbnail(),
                            new WebEmbeddedWithThumbnail(),
                            new AndroidVrWithThumbnail()));
            LazoDiscs.LOGGER.info("LazoDiscs registered youtube-source for LavaPlayer ({})", label);
        } catch (Throwable t) {
            LazoDiscs.LOGGER.warn(
                    "LazoDiscs could not register youtube-source ({}): {}", label, t.toString());
        }

        try {
            // Register sources explicitly: LavaPlayer's helper also loads its legacy YouTube
            // source, which is deliberately excluded from the distributed archive.
            manager.registerSourceManager(new YandexMusicAudioSourceManager(true));
            manager.registerSourceManager(new SoundCloudAacSourceManager());
            manager.registerSourceManager(new BandcampAudioSourceManager());
            manager.registerSourceManager(new VimeoAudioSourceManager());
            manager.registerSourceManager(new TwitchStreamAudioSourceManager());
            manager.registerSourceManager(new BeamAudioSourceManager());
            manager.registerSourceManager(new GetyarnAudioSourceManager());
            manager.registerSourceManager(new NicoAudioSourceManager());
            manager.registerSourceManager(new HttpAudioSourceManager());
            manager.registerSourceManager(new LocalAudioSourceManager());
        } catch (Throwable t) {
            LazoDiscs.LOGGER.warn(
                    "LazoDiscs could not register default LavaPlayer source managers ({}): {}",
                    label,
                    t.toString());
        }
        return manager;
    }

    public static StreamingPlayback openStream(String rawUrl, String title, float volume)
            throws InterruptedException {
        // Stored or externally supplied disc data must obey current URL restrictions too.
        ResolveRequest request = resolveIdentifier(DiscDataUtil.validateUrl(rawUrl), title);
        LazoDiscs.LOGGER.info(
                "LazoDiscs resolving streaming audio with LavaPlayer: '{}' -> '{}'",
                rawUrl,
                request.identifier());
        AudioTrack track = loadTrack(PLAYER_MANAGER, request.identifier(), request.metadata());
        validateStreamingTrackLength(track);

        AudioPlayer player = PLAYER_MANAGER.createPlayer();
        player.setVolume(Math.max(0, Math.round(Math.max(0.0F, volume) * 100.0F)));
        player.playTrack(track);
        return new StreamingPlayback(player, track);
    }

    public static ResolvedTrack resolveTrack(String rawUrl, String fallbackTitle)
            throws InterruptedException {
        ResolveRequest request = resolveIdentifier(DiscDataUtil.validateUrl(rawUrl), fallbackTitle);
        AudioTrack track = loadTrack(PLAYER_MANAGER, request.identifier(), request.metadata());
        validateStreamingTrackLength(track);

        AudioTrackInfo info = track.getInfo();
        return new ResolvedTrack(
                nullToUnknown(info.title),
                nullToUnknown(info.author),
                info.uri == null || info.uri.isBlank() ? info.identifier : info.uri,
                info.length);
    }

    public static List<SearchResult> search(String input, int maxResults)
            throws InterruptedException {
        String cleanInput = input == null ? "" : input.trim();
        if (cleanInput.isBlank()) return List.of();
        return searchYoutubeMusic(cleanInput, maxResults, null);
    }

    public static List<SearchResult> searchYoutubeMusic(String query, int maxResults)
            throws InterruptedException {
        return searchYoutubeMusic(query, maxResults, null);
    }

    public static List<SearchResult> searchYoutubeMusic(
            String query, int maxResults, TrackMetadata metadata) throws InterruptedException {
        String cleanQuery = query == null ? "" : query.trim();
        if (cleanQuery.isBlank()) return List.of();

        CountDownLatch latch = new CountDownLatch(1);
        List<SearchResult> results = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        String identifier = "ytmsearch:" + cleanQuery;

        PLAYER_MANAGER.loadItemOrdered(
                "lazodiscs-search:" + cleanQuery,
                identifier,
                new AudioLoadResultHandler() {
                    @Override
                    public void trackLoaded(AudioTrack track) {
                        addSearchResult(results, track);
                        latch.countDown();
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist playlist) {
                        for (AudioTrack track : playlist.getTracks()) {
                            addSearchResult(results, track);
                            if (results.size() >= maxResults) break;
                        }
                        latch.countDown();
                    }

                    @Override
                    public void noMatches() {
                        latch.countDown();
                    }

                    @Override
                    public void loadFailed(FriendlyException exception) {
                        failure.set(exception);
                        latch.countDown();
                    }
                });

        int timeout = LazoDiscsConfig.LAVAPLAYER_LOAD_TIMEOUT_SECONDS.get();
        if (!latch.await(timeout, TimeUnit.SECONDS)) {
            throw new RuntimeException(LazoDiscsText.searchTimedOut(timeout));
        }
        if (failure.get() != null) {
            throw new RuntimeException(messageOf(failure.get()));
        }
        if (metadata != null) {
            results.sort(
                    (a, b) ->
                            Integer.compare(
                                    scoreSearchResult(b, metadata),
                                    scoreSearchResult(a, metadata)));
        }
        return List.copyOf(results);
    }

    private static AudioTrack loadTrack(
            AudioPlayerManager manager, String identifier, TrackMetadata metadata)
            throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AudioTrack> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        manager.loadItemOrdered(
                "lazodiscs-load:" + identifier,
                identifier,
                new AudioLoadResultHandler() {
                    @Override
                    public void trackLoaded(AudioTrack track) {
                        result.set(track);
                        latch.countDown();
                    }

                    @Override
                    public void playlistLoaded(AudioPlaylist playlist) {
                        if (metadata != null) {
                            AudioTrack selected = selectBestTrack(playlist.getTracks(), metadata);
                            if (selected == null) {
                                failure.set(new RuntimeException(LazoDiscsText.audioNoMatches()));
                            } else {
                                result.set(selected);
                            }
                        } else if (playlist.getSelectedTrack() != null) {
                            result.set(playlist.getSelectedTrack());
                        } else if (!playlist.getTracks().isEmpty()) {
                            result.set(selectBestTrack(playlist.getTracks(), metadata));
                        } else {
                            failure.set(new RuntimeException(LazoDiscsText.audioNoMatches()));
                        }
                        latch.countDown();
                    }

                    @Override
                    public void noMatches() {
                        failure.set(new RuntimeException(LazoDiscsText.audioNoMatches()));
                        latch.countDown();
                    }

                    @Override
                    public void loadFailed(FriendlyException exception) {
                        failure.set(exception);
                        latch.countDown();
                    }
                });

        int timeout = LazoDiscsConfig.LAVAPLAYER_LOAD_TIMEOUT_SECONDS.get();
        if (!latch.await(timeout, TimeUnit.SECONDS)) {
            throw new RuntimeException(LazoDiscsText.audioResolveTimedOut(timeout));
        }
        if (failure.get() != null) {
            throw new RuntimeException(messageOf(failure.get()));
        }
        if (result.get() == null) {
            throw new RuntimeException(LazoDiscsText.audioNoMatches());
        }
        return result.get();
    }

    private static void addSearchResult(List<SearchResult> results, AudioTrack track) {
        if (track == null || track.getInfo() == null) return;
        AudioTrackInfo info = track.getInfo();
        String url = info.uri;
        if (url == null || url.isBlank()) {
            url = info.identifier == null ? "" : info.identifier;
        }
        if (url.isBlank()) return;
        results.add(
                new SearchResult(
                        nullToUnknown(info.title), nullToUnknown(info.author), url, info.length));
    }

    private static String nullToUnknown(String value) {
        return value == null || value.isBlank() ? "Unknown" : value;
    }

    private static void validateStreamingTrackLength(AudioTrack track) {
        int maxSeconds = LazoDiscsConfig.MAX_STREAMING_TRACK_LENGTH_SECONDS.get();
        if (maxSeconds > 0
                && track != null
                && track.getDuration() > 0
                && track.getDuration() > maxSeconds * 1000L) {
            throw new IllegalArgumentException(LazoDiscsText.trackTooLong(maxSeconds));
        }
    }

    private static AudioTrack selectBestTrack(List<AudioTrack> tracks, TrackMetadata metadata) {
        if (tracks.isEmpty()) return null;
        if (metadata == null) return tracks.get(0);

        AudioTrack best = null;
        int bestScore = Integer.MIN_VALUE;
        for (AudioTrack track : tracks) {
            int score = scoreTrack(track, metadata);
            AudioTrackInfo info = track.getInfo();
            LazoDiscs.LOGGER.info(
                    "LazoDiscs Spotify candidate score {}: '{}' by '{}' ({} ms)",
                    score,
                    info.title,
                    info.author,
                    info.length);
            // Duration and artist hints must not select a completely different song.
            if (!TrackMatchScorer.matchesTitle(info.title, metadata.title())) continue;
            if (score > bestScore) {
                bestScore = score;
                best = track;
            }
        }
        if (best == null) return null;
        AudioTrackInfo info = best.getInfo();
        LazoDiscs.LOGGER.info(
                "LazoDiscs selected Spotify candidate: '{}' by '{}' with score {}",
                info.title,
                info.author,
                bestScore);
        return best;
    }

    private static int scoreTrack(AudioTrack track, TrackMetadata metadata) {
        AudioTrackInfo info = track.getInfo();
        return TrackMatchScorer.score(info.title, info.author, info.length, metadata);
    }

    private static int scoreSearchResult(SearchResult result, TrackMetadata metadata) {
        return TrackMatchScorer.score(result.title(), result.author(), result.lengthMs(), metadata);
    }

    private static String messageOf(Throwable t) {
        if (t == null) return LazoDiscsText.unknown();
        String message = t.getMessage();
        Throwable cause = t.getCause();
        if ((message == null || message.isBlank()) && cause != null) return messageOf(cause);
        if (cause != null && message != null && message.equals(cause.toString()))
            return messageOf(cause);
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private static ResolveRequest resolveIdentifier(String raw, String fallbackTitle) {
        if (SpotifyTitleResolver.looksLikeSpotify(raw)) {
            if (!LazoDiscsConfig.SPOTIFY_SEARCH_VIA_YOUTUBE.get()) {
                throw new IllegalArgumentException(LazoDiscsText.spotifyDisabled());
            }
            TrackMetadata metadata =
                    SpotifyTitleResolver.resolveMetadata(raw)
                            .map(
                                    spotify ->
                                            new TrackMetadata(
                                                    spotify.title(),
                                                    spotify.artists(),
                                                    spotify.durationMs()))
                            .orElse(null);
            String query;
            if (metadata != null && !metadata.searchQuery().isBlank()) {
                query = metadata.searchQuery();
            } else {
                query =
                        Optional.ofNullable(fallbackTitle)
                                .filter(s -> !s.isBlank() && !s.equals(raw))
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        LazoDiscsText.spotifyMetadataFailed()));
            }
            return new ResolveRequest("ytmsearch:" + query, metadata);
        }

        try {
            URI uri = URI.create(raw);
            if (uri.getScheme() == null || uri.getScheme().isBlank())
                return new ResolveRequest("ytmsearch:" + raw, null);
        } catch (Exception ignored) {
            return new ResolveRequest("ytmsearch:" + raw, null);
        }
        return new ResolveRequest(raw, null);
    }

    public record SearchResult(String title, String author, String url, long lengthMs) {}

    public record ResolvedTrack(String title, String author, String url, long lengthMs) {}

    public record StreamingPlayback(AudioPlayer player, AudioTrack track) implements AutoCloseable {
        @Override
        public void close() {
            try {
                player.stopTrack();
            } catch (Exception ignored) {
            }
            try {
                player.destroy();
            } catch (Exception ignored) {
            }
        }
    }

    private record ResolveRequest(String identifier, TrackMetadata metadata) {}
}
