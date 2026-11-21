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

    @Test void hlsPlaybackKeepsTheSessionCookieReceivedDuringTheProbe() throws Exception {
        try (var fixture = new Fixture()) {
            var hits=new AtomicInteger();
            fixture.http.createContext("/cookie-inner.m3u8", exchange -> {
                int attempt=hits.getAndIncrement();
                String cookie=exchange.getRequestHeaders().getFirst("Cookie");
                if(attempt>0 && (cookie==null || !cookie.contains("lazo-session=fixture-cookie"))) {
                    exchange.sendResponseHeaders(403,-1);exchange.close();return;
                }
                byte[] body=("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:0.4,\n"
                    +fixture.allowed("/tone.ts?signature=exact%2Bsegment")+"\n#EXT-X-ENDLIST\n").getBytes(StandardCharsets.UTF_8);
                if(attempt==0)exchange.getResponseHeaders().set("Set-Cookie","lazo-session=fixture-cookie; Path=/");
                exchange.getResponseHeaders().set("Content-Type","application/vnd.apple.mpegurl");
                exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
            });
            var player=fixture.manager.createPlayer();
            try {
                AudioTrack own = fixture.load(fixture.allowed("/cookie-inner.m3u8"));
                assertThrows(ExecutionException.class, () -> fixture.load(fixture.allowed("/cookie-inner.m3u8")),
                        "An unrelated load must not inherit the first recording's cookie");
                AudioPlaybackPreparation.start(player,own,null);
                assertNotNull(player.provide());assertTrue(hits.get()>=2);assertTrue(fixture.hlsSegmentRequests.get()>0);
            } finally {player.destroy();}
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

    @Test void redirectedMasterResolvesItsRelativeVariantAgainstEffectiveUri() throws Exception {
        relativeRedirect(true);
    }
    @Test void redirectedMediaPlaylistResolvesItsRelativeSegmentAgainstEffectiveUri() throws Exception {
        relativeRedirect(false);
    }
    private static void relativeRedirect(boolean master) throws Exception {
        try (var fixture = new Fixture()) {
            var initialHits = new AtomicInteger();
            var actualChildren = new AtomicInteger();
            String initial = master ? "/relative-master.m3u8" : "/relative-media.m3u8";
            String effective = master ? "/effective/master/main.m3u8" : "/effective/media/inner.m3u8";
            String media = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:0.4,\ntone.ts?signature=exact%2Bsegment\n#EXT-X-ENDLIST\n";
            String playlist = master ? "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000\nvariant/inner.m3u8?signature=exact%2Bplaylist\n" : media;
            fixture.http.createContext(initial, e -> {
                if (initialHits.incrementAndGet() > 1) {
                    e.getResponseHeaders().set("Location", effective);e.sendResponseHeaders(302,-1);
                } else {
                    byte[] body=playlist.getBytes(StandardCharsets.UTF_8);
                    e.getResponseHeaders().set("Content-Type","application/vnd.apple.mpegurl");
                    e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);
                }
                e.close();
            });
            fixture.http.createContext("/effective/", e -> {
                String path=e.getRequestURI().getPath();byte[] body;
                if(path.equals(effective) || path.equals("/effective/master/variant/inner.m3u8")) {
                    if(path.endsWith("variant/inner.m3u8")) {
                        actualChildren.incrementAndGet();
                        assertEquals("signature=exact%2Bplaylist", e.getRequestURI().getRawQuery());
                    }
                    body=(path.equals(effective)?playlist:media).getBytes(StandardCharsets.UTF_8);
                    e.getResponseHeaders().set("Content-Type","application/vnd.apple.mpegurl");
                } else if(path.equals("/effective/master/variant/tone.ts") || path.equals("/effective/media/tone.ts")) {
                    actualChildren.incrementAndGet();
                    assertEquals("signature=exact%2Bsegment",e.getRequestURI().getRawQuery());
                    try(var in=DirectMediaSourceTest.class.getResourceAsStream("/audio/hls-control.ts")){body=in.readAllBytes();}
                    e.getResponseHeaders().set("Content-Type","video/mp2t");
                } else { e.sendResponseHeaders(404,-1);e.close();return; }
                e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
            });
            var player=fixture.manager.createPlayer();
            try {
                AudioPlaybackPreparation.start(player,fixture.load(fixture.allowed(initial)),null);
                var frame=player.provide();assertNotNull(frame,"Relative redirect must reach real AAC decoder");
                boolean nonzero=false;for(byte sample:frame.getData())nonzero|=sample!=0;
                assertTrue(nonzero);assertTrue(actualChildren.get()>=(master?2:1));
                assertTrue(initialHits.get()>=2);assertEquals(0,fixture.forbiddenRequests.get());
            } finally { player.destroy(); }
        }
    }

    @Test void directAudioKeepsItsOwnProbeCookieWithoutSharingAccessWithAnotherRecording() throws Exception {
        try (var fixture = new Fixture()) {
            var hits = new AtomicInteger();
            fixture.http.createContext("/cookie-audio.wav", e -> {
                int attempt = hits.getAndIncrement();
                String cookie = e.getRequestHeaders().getFirst("Cookie");
                if (attempt > 0 && (cookie == null || !cookie.contains("lazo-session=private-audio"))) {
                    e.sendResponseHeaders(403, -1);e.close();return;
                }
                if (attempt == 0) e.getResponseHeaders().set("Set-Cookie", "lazo-session=private-audio; Path=/");
                byte[] body = wav();e.getResponseHeaders().set("Content-Type", "audio/wav");
                e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
            });
            AudioTrack own = fixture.load(fixture.allowed("/cookie-audio.wav?token=owner"));
            assertThrows(ExecutionException.class, () -> fixture.load(fixture.allowed("/cookie-audio.wav")));
            var player = fixture.manager.createPlayer();
            try {
                AudioPlaybackPreparation.start(player, own, null);
                assertNotNull(player.provide(), "The same recording must retain its cookie on direct replay");
                assertTrue(hits.get() >= 3);
            } finally { player.destroy(); }
        }
    }
}
