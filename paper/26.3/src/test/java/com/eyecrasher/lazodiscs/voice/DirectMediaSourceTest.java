package com.eyecrasher.lazodiscs.voice;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises LavaPlayer's real HTTP probes, redirects and HLS decoder requests. */
class DirectMediaSourceTest {
    private static final class Fixture implements AutoCloseable {
        final HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final CooperativeAudioPlayerManager manager = new CooperativeAudioPlayerManager();
        final AtomicInteger forbiddenRequests = new AtomicInteger();
        final AtomicInteger allowedRequests = new AtomicInteger();
        final AtomicInteger hlsPlaylistRequests = new AtomicInteger();
        final AtomicInteger hlsSegmentRequests = new AtomicInteger();
        final AtomicBoolean redirectPlayback = new AtomicBoolean();
        volatile String query;
        volatile String playlistQuery,segmentQuery;

        Fixture() throws Exception {
            manager.getConfiguration().setOutputFormat(StandardAudioDataFormats.DISCORD_PCM_S16_LE);
            manager.registerSourceManager(new DirectMediaSource(url -> {
                if (!"127.0.0.1".equals(URI.create(url).getHost())) {
                    throw new IllegalArgumentException("Host blocked by test policy: " + URI.create(url).getHost());
                }
            }));
            http.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if (path.startsWith("/forbidden")) forbiddenRequests.incrementAndGet();
                else allowedRequests.incrementAndGet();
                if (path.equals("/redirect.wav") || (path.equals("/late.wav") && redirectPlayback.get())) {
                    exchange.getResponseHeaders().set("Location", denied("/forbidden.wav"));
                    exchange.sendResponseHeaders(302, -1);
                } else if (path.equals("/blocked-playlist-redirect.m3u8") || path.equals("/blocked-segment-redirect.ts")) {
                    exchange.getResponseHeaders().set("Location", denied(path.startsWith("/blocked-playlist")
                            ? "/forbidden.m3u8" : "/forbidden.ts"));
                    exchange.sendResponseHeaders(302, -1);
                } else if (path.equals("/playlist-redirect.m3u8") || path.equals("/segment-redirect.ts")) {
                    exchange.getResponseHeaders().set("Location", allowed(path.startsWith("/playlist")
                            ? "/allowed-inner.m3u8?signature=exact%2Bplaylist" : "/tone.ts?signature=exact%2Bsegment"));
                    exchange.sendResponseHeaders(302, -1);
                } else {
                    byte[] body;
                    String type;
                    if (path.equals("/redirected-master.m3u8")) {
                        body = ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000\n" + allowed("/blocked-playlist-redirect.m3u8") + "\n")
                                .getBytes(StandardCharsets.UTF_8);
                        type = "application/vnd.apple.mpegurl";
                    } else if (path.equals("/redirected-segments.m3u8")) {
                        body = ("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:1.0,\n"
                                + allowed("/blocked-segment-redirect.ts") + "\n#EXT-X-ENDLIST\n").getBytes(StandardCharsets.UTF_8);
                        type = "application/vnd.apple.mpegurl";
                    } else if (path.equals("/allowed-outer.m3u8")) {
                        body = ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000\n" + allowed("/playlist-redirect.m3u8") + "\n")
                                .getBytes(StandardCharsets.UTF_8);
                        type = "application/vnd.apple.mpegurl";
                    } else if (path.equals("/allowed-inner.m3u8")) {
                        hlsPlaylistRequests.incrementAndGet();
                        playlistQuery=exchange.getRequestURI().getRawQuery();
                        body = ("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:0.4,\n"
                                + allowed("/segment-redirect.ts") + "\n#EXT-X-ENDLIST\n").getBytes(StandardCharsets.UTF_8);
                        type = "application/vnd.apple.mpegurl";
                    } else if (path.equals("/tone.ts")) {
                        hlsSegmentRequests.incrementAndGet();
                        segmentQuery=exchange.getRequestURI().getRawQuery();
                        try (var input = DirectMediaSourceTest.class.getResourceAsStream("/audio/hls-control.ts")) {
                            if (input == null) throw new java.io.IOException("Missing generated HLS control audio");
                            body = input.readAllBytes();
                        }
                        type = "video/mp2t";
                    } else if (path.equals("/list.m3u")) {
                        body = ("#EXTM3U\n" + denied("/forbidden.wav") + "\n").getBytes(StandardCharsets.UTF_8);
                        type = "audio/x-mpegurl";
                    } else if (path.equals("/master.m3u8")) {
                        body = ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=128000\n" + denied("/forbidden.m3u8") + "\n")
                                .getBytes(StandardCharsets.UTF_8);
                        type = "application/vnd.apple.mpegurl";
                    } else if (path.equals("/segments.m3u8")) {
                        body = ("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:1.0,\n"
                                + denied("/forbidden.ts") + "\n#EXT-X-ENDLIST\n").getBytes(StandardCharsets.UTF_8);
                        type = "application/vnd.apple.mpegurl";
                    } else {
                        query = exchange.getRequestURI().getRawQuery();
                        body = wav();
                        type = "audio/wav";
                    }
                    exchange.getResponseHeaders().set("Content-Type", type);
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                exchange.close();
            });
            http.start();
        }

        String allowed(String path) { return "http://127.0.0.1:" + http.getAddress().getPort() + path; }
        String denied(String path) { return "http://localhost:" + http.getAddress().getPort() + path; }

        AudioTrack load(String url) throws Exception {
            var result = new CompletableFuture<AudioTrack>();
            manager.loadItem(url, new AudioLoadResultHandler() {
                public void trackLoaded(AudioTrack track) { result.complete(track); }
                public void playlistLoaded(AudioPlaylist list) { result.completeExceptionally(new AssertionError("Unexpected playlist")); }
                public void noMatches() { result.completeExceptionally(new AssertionError("No audio match")); }
                public void loadFailed(FriendlyException error) { result.completeExceptionally(error); }
            });
            return result.get(5, TimeUnit.SECONDS);
        }

        Throwable playbackFailure(AudioTrack track) throws Exception {
            var result = new CompletableFuture<Throwable>();
            var player = manager.createPlayer();
            player.addListener(new AudioEventAdapter() {
                @Override public void onTrackException(com.sedmelluq.discord.lavaplayer.player.AudioPlayer player,
                        AudioTrack failed, FriendlyException error) { result.complete(error); }
            });
            try {
                player.playTrack(track);
                return result.get(5, TimeUnit.SECONDS);
            } finally { player.destroy(); }
        }

        public void close() { manager.shutdown(); http.stop(0); }
    }

    private static void blocked(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (String.valueOf(cause.getMessage()).contains("Host blocked by test policy")) return;
        }
        fail("Expected a URL policy refusal", error);
    }

    @Test void permittedSignedUrlStillLoadsAndProducesAudio() throws Exception {
        try (var fixture = new Fixture()) {
            var player = fixture.manager.createPlayer();
            try {
                AudioPlaybackPreparation.start(player, fixture.load(fixture.allowed("/song.wav?token=exact%2Bsignature")), null);
                assertNotNull(player.provide(), "The permitted control must reach the real WAV decoder");
                assertEquals("token=exact%2Bsignature", fixture.query);
                assertTrue(fixture.allowedRequests.get() >= 2, "Probe and playback both request the media");
                assertEquals(0, fixture.forbiddenRequests.get());
            } finally { player.destroy(); }
        }
    }

    @Test void initialAndRedirectedHttpProbesNeverContactDeniedHost() throws Exception {
        try (var fixture = new Fixture()) {
            blocked(assertThrows(ExecutionException.class, () -> fixture.load(fixture.denied("/forbidden.wav"))));
            assertEquals(0, fixture.allowedRequests.get());
            blocked(assertThrows(ExecutionException.class, () -> fixture.load(fixture.allowed("/redirect.wav"))));
            assertTrue(fixture.allowedRequests.get() > 0, "The allowed redirect endpoint must be exercised");
            assertEquals(0, fixture.forbiddenRequests.get());
        }
    }

    @Test void playlistReferenceCannotBypassHostPolicy() throws Exception {
        try (var fixture = new Fixture()) {
            blocked(assertThrows(ExecutionException.class, () -> fixture.load(fixture.allowed("/list.m3u"))));
            assertTrue(fixture.allowedRequests.get() > 0);
            assertEquals(0, fixture.forbiddenRequests.get());
        }
    }

    @Test void decoderRechecksRedirectsAfterSuccessfulMetadataProbe() throws Exception {
        try (var fixture = new Fixture()) {
            AudioTrack track = fixture.load(fixture.allowed("/late.wav"));
            int probes = fixture.allowedRequests.get();
            fixture.redirectPlayback.set(true);
            blocked(fixture.playbackFailure(track));
            assertTrue(fixture.allowedRequests.get() > probes, "Playback must reopen the allowed media");
            assertEquals(0, fixture.forbiddenRequests.get());
        }
    }

    @Test void hlsVariantAndSegmentRequestsUseTheSameHostPolicy() throws Exception {
        try (var fixture = new Fixture()) {
            blocked(fixture.playbackFailure(fixture.load(fixture.allowed("/master.m3u8"))));
            blocked(fixture.playbackFailure(fixture.load(fixture.allowed("/segments.m3u8"))));
            assertTrue(fixture.allowedRequests.get() >= 4, "Both HLS probes and playback requests must run");
            assertEquals(0, fixture.forbiddenRequests.get());
        }
    }

    @Test void permittedHlsPlaylistAndSegmentRedirectsProduceDecodedAudio() throws Exception {
        try (var fixture = new Fixture()) {
            var player = fixture.manager.createPlayer();
            try {
                AudioPlaybackPreparation.start(player, fixture.load(fixture.allowed("/allowed-outer.m3u8")), null);
                var frame = player.provide();
                assertNotNull(frame,"The permitted redirect control must reach the real MPEG-TS/AAC decoder");
                boolean nonzero=false;
                for (byte sample:frame.getData()) nonzero |= sample!=0;
                assertTrue(nonzero,"The decoded control tone must not be silent");
                assertTrue(fixture.hlsPlaylistRequests.get()>0);assertTrue(fixture.hlsSegmentRequests.get()>0);
                assertEquals("signature=exact%2Bplaylist",fixture.playlistQuery);
                assertEquals("signature=exact%2Bsegment",fixture.segmentQuery);
                assertEquals(0,fixture.forbiddenRequests.get());
            } finally { player.destroy(); }
        }
    }

    @Test void hlsPlaylistAndSegmentRedirectsNeverContactDeniedHost() throws Exception {
        try (var fixture = new Fixture()) {
            blocked(fixture.playbackFailure(fixture.load(fixture.allowed("/redirected-master.m3u8"))));
            blocked(fixture.playbackFailure(fixture.load(fixture.allowed("/redirected-segments.m3u8"))));
            assertTrue(fixture.allowedRequests.get()>=6,"Both allowed HLS redirect endpoints must run");
            assertEquals(0,fixture.forbiddenRequests.get());
        }
    }

    private static byte[] wav() {
        int samples = 4800;
        ByteBuffer out = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples * 2);
        out.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
        out.putInt(48000).putInt(96000).putShort((short) 2).putShort((short) 16);
        out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
        for (int i = 0; i < samples; i++) out.putShort((short) (Math.sin(i * 0.1) * 12000));
        return out.array();
    }
}
