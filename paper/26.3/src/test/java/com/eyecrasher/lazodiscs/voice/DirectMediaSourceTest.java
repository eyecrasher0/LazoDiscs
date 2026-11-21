package com.eyecrasher.lazodiscs.voice;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
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
            this(uri -> {});
        }

        Fixture(Consumer<URI> policy, AudioSourceManager... services) throws Exception {
            manager.getConfiguration().setOutputFormat(StandardAudioDataFormats.DISCORD_PCM_S16_LE);
            manager.registerSourceManagers(services);
            manager.registerSourceManager(new DirectMediaSource(url -> {
                URI uri = URI.create(url);
                if (!"127.0.0.1".equals(uri.getHost())) {
                    throw new IllegalArgumentException("Host blocked by test policy: " + uri.getHost());
                }
                policy.accept(uri);
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

    @Test void validatedHttpAndM3uRedirectsReturnToTheRegisteredService() throws Exception {
        var service = new BasicAudioPlaylist("service fixture", List.of(), null, false);
        var visits = new ConcurrentLinkedQueue<String>();
        var endpoints = new HttpAudioSourceManager() {
            @Override public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
                if (!URI.create(reference.identifier).getPath().startsWith("/service/")) return null;
                visits.add(reference.identifier);
                return service;
            }
        };
        try (var fixture = new Fixture(uri -> {
            if (uri.getPath().equals("/service/denied")) throw new IllegalArgumentException("Denied by test policy");
        }, endpoints)) {
            String target = fixture.allowed("/service/track?signature=exact%2B%2f");
            fixture.http.createContext("/service-redirect.wav", exchange -> {
                try (exchange) { redirect(exchange, target); }
            });
            fixture.http.createContext("/service-list.m3u", exchange -> {
                try (exchange) { respond(exchange, "audio/x-mpegurl", ("#EXTM3U\n" + target + "\n").getBytes(StandardCharsets.UTF_8)); }
            });
            fixture.http.createContext("/blocked-service.wav", exchange -> {
                try (exchange) { redirect(exchange, fixture.allowed("/service/denied")); }
            });
            assertSame(service, fixture.manager.loadItemSync(fixture.allowed("/service-redirect.wav")));
            assertSame(service, fixture.manager.loadItemSync(fixture.allowed("/service-list.m3u")));
            assertThrows(IllegalArgumentException.class, () -> fixture.manager.loadItemSync(fixture.allowed("/blocked-service.wav")));
            assertEquals(List.of(target, target), List.copyOf(visits));
        }
    }

    @Test void emptyLocationRetriesTheExactSignedUrlWithTheRecordingsOwnCookie() throws Exception {
        try (var fixture = new Fixture()) {
            String target = "/signed/song.wav?token=exact%2B%2f&name=track%20one";
            String cookie = "lazo-session=redirect-fixture";
            var requests = new ConcurrentLinkedQueue<String>();
            var initial = new AtomicBoolean(true);
            var authorized = new AtomicInteger();
            var denied = new AtomicInteger();
            fixture.http.createContext("/signed/", exchange -> {
                try (exchange) {
                    String requested = exchange.getRequestURI().toString();
                    requests.add(requested);
                    if (!target.equals(requested)) {
                        exchange.sendResponseHeaders(404, -1);
                    } else if (initial.getAndSet(false)) {
                        exchange.getResponseHeaders().set("Set-Cookie", cookie + "; Path=/signed/");
                        redirect(exchange, "");
                    } else if (cookie.equals(exchange.getRequestHeaders().getFirst("Cookie"))) {
                        authorized.incrementAndGet();
                        respond(exchange, "audio/wav", wav());
                    } else {
                        denied.incrementAndGet();
                        exchange.sendResponseHeaders(403, -1);
                    }
                }
            });
            String url = fixture.allowed(target);
            AudioTrack track = fixture.load(url);
            assertEquals(url, track.getIdentifier());
            assertPlays(fixture, track);
            assertTrue(authorized.get() >= 2, "Both the redirected probe and decoder must use the session cookie");
            assertThrows(ExecutionException.class, () -> fixture.load(url));
            assertEquals(1, denied.get(), "An unrelated recording must arrive without the session cookie");
            assertTrue(requests.stream().allMatch(target::equals), requests.toString());
        }
    }

    @Test void basicAuthStaysWithTheRecordingAcrossRedirectsAndPlayback() throws Exception {
        for (boolean hls : List.of(false, true)) {
            try (var fixture = new Fixture()) {
                String suffix = hls ? ".m3u8" : ".wav";
                String resource = "/private/recording" + suffix;
                byte[] audio = hls ? segment() : wav();
                fixture.http.createContext("/private/", exchange -> {
                    try (exchange) {
                        if (!"Basic Zml4dHVyZTpwYXNz".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                            exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"recordings\"");
                            exchange.sendResponseHeaders(401, -1);
                        } else if (exchange.getRequestURI().getPath().equals("/private/start" + suffix)) {
                            redirect(exchange, fixture.allowed(resource));
                        } else if (exchange.getRequestURI().getPath().endsWith(".m3u8")) {
                            respond(exchange, "application/vnd.apple.mpegurl", playlist("tone.ts"));
                        } else {
                            respond(exchange, hls ? "video/mp2t" : "audio/wav", audio);
                        }
                    }
                });
                String entry = fixture.allowed("/private/start" + suffix).replace("://", "://fixture:pass@");
                var recording = fixture.load(entry);
                assertEquals(fixture.allowed(resource), recording.getIdentifier());
                var refusal = assertThrows(ExecutionException.class, () -> fixture.load(fixture.allowed(resource)));
                assertTrue(refusal.getCause().getCause().getMessage().contains("401"));
                assertPlays(fixture, recording);
            }
        }
    }

    @Test void hlsChecksTheOriginalUriOfBothKindsOfChildRequest() throws Exception {
        try (var fixture = new Fixture(uri -> {
            if (uri.getRawUserInfo() != null) throw new IllegalArgumentException("User info blocked by test policy");
        })) {
            String child = fixture.allowed("/forbidden-child").replace("://", "://fixture:pass@");
            fixture.http.createContext("/variants.m3u8", exchange -> {
                try (exchange) {
                    respond(exchange, "application/vnd.apple.mpegurl",
                            ("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000\n" + child + "\n").getBytes(StandardCharsets.UTF_8));
                }
            });
            fixture.http.createContext("/media.m3u8", exchange -> {
                try (exchange) {
                    respond(exchange, "application/vnd.apple.mpegurl", playlist(child));
                }
            });
            for (String path : List.of("/variants.m3u8", "/media.m3u8")) {
                Throwable failure = fixture.playbackFailure(fixture.load(fixture.allowed(path)));
                while (failure.getCause() != null) failure = failure.getCause();
                assertEquals("User info blocked by test policy", failure.getMessage());
            }
            assertEquals(0, fixture.forbiddenRequests.get());
        }
    }

    @Test void queryOnlyRedirectsAndHlsChildrenKeepTheirPathAndRawQuery() throws Exception {
        try (var fixture = new Fixture()) {
            String direct = "/live/song.wav";
            String hls = "/live/index.m3u8";
            var received = new ConcurrentLinkedQueue<String>();
            byte[] tone = segment();
            fixture.http.createContext("/live/", exchange -> {
                try (exchange) {
                    var target = exchange.getRequestURI();
                    received.add(target.toString());
                    String query = target.getRawQuery();
                    if (target.getPath().equals(direct)) {
                        if ("start".equals(query)) redirect(exchange, "?signature=wave%2B");
                        else if ("signature=wave%2B".equals(query)) respond(exchange, "audio/wav", wav());
                        else exchange.sendResponseHeaders(404, -1);
                    } else if (target.getPath().equals(hls) && query != null) {
                        switch (query) {
                            case "start" -> redirect(exchange, "?master=on%2F");
                            case "master=on%2F" -> respond(exchange, "application/vnd.apple.mpegurl",
                                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=64000\n?variant=low%2B\n".getBytes(StandardCharsets.UTF_8));
                            case "variant=low%2B" -> redirect(exchange, "?playlist=audio%2f");
                            case "playlist=audio%2f" -> respond(exchange, "application/vnd.apple.mpegurl", playlist("?part=1%2B"));
                            case "part=1%2B" -> redirect(exchange, "?signature=exact%2B%2F");
                            case "signature=exact%2B%2F" -> respond(exchange, "video/mp2t", tone);
                            default -> exchange.sendResponseHeaders(404, -1);
                        }
                    } else exchange.sendResponseHeaders(404, -1);
                }
            });
            assertPlays(fixture, fixture.load(fixture.allowed(direct + "?start")));
            assertPlays(fixture, fixture.load(fixture.allowed(hls + "?start")));
            assertTrue(received.containsAll(List.of(direct + "?signature=wave%2B", hls + "?master=on%2F",
                    hls + "?variant=low%2B", hls + "?playlist=audio%2f", hls + "?part=1%2B", hls + "?signature=exact%2B%2F")),
                    received.toString());
            assertTrue(received.stream().noneMatch(uri -> uri.startsWith("/live/?")), received.toString());
        }
    }

    private static void assertPlays(Fixture fixture, AudioTrack track) throws Exception {
        var player = fixture.manager.createPlayer();
        try {
            AudioPlaybackPreparation.start(player, track, null);
            assertNotNull(player.provide(), "The decoder must provide audio for the permitted recording");
        } finally { player.destroy(); }
    }

    private static byte[] playlist(String child) {
        return ("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:0.4,\n"
                + child + "\n#EXT-X-ENDLIST\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] segment() throws IOException {
        try (var input = DirectMediaSourceTest.class.getResourceAsStream("/audio/hls-control.ts")) {
            return input.readAllBytes();
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private static void respond(HttpExchange exchange, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
    }
    @Test void standaloneHlsMediaPlaylistPlaysEveryRelativeSegment() throws Exception { standaloneMedia(false); }
    @Test void standaloneHlsMediaPlaylistPlaysEveryAbsoluteSegment() throws Exception { standaloneMedia(true); }
    private static void standaloneMedia(boolean absolute) throws Exception {
        try (var fixture = new Fixture()) {
            var children=new java.util.HashSet<String>();
            fixture.http.createContext("/chunks/", e -> {
                synchronized(children){children.add(e.getRequestURI().getPath());}
                byte[] body;
                try(var in=DirectMediaSourceTest.class.getResourceAsStream("/audio/hls-control.ts")){body=in.readAllBytes();}
                e.getResponseHeaders().set("Content-Type","video/mp2t");
                e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
            });
            fixture.http.createContext("/standalone.m3u8", e -> {
                String one=absolute?fixture.allowed("/chunks/one.ts"):"chunks/one.ts";
                String two=absolute?fixture.allowed("/chunks/two.ts"):"chunks/two.ts";
                byte[] body=("#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:0.4,\n"+one+"\n#EXTINF:0.4,\n"+two+"\n#EXT-X-ENDLIST\n").getBytes(StandardCharsets.UTF_8);
                e.getResponseHeaders().set("Content-Type","application/vnd.apple.mpegurl");
                e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();
            });
            int single=countDecodedFrames(fixture,fixture.allowed("/allowed-outer.m3u8"));
            int multiple=countDecodedFrames(fixture,fixture.allowed("/standalone.m3u8"));
            System.out.println("standalone="+absolute+" singleFrames="+single+" twoSegmentFrames="+multiple+" children="+children);
            assertTrue(single>0);assertTrue(multiple>=single*1.8,"Direct media playlist must play beyond its first segment: "+single+" vs "+multiple);
            synchronized(children){assertEquals(java.util.Set.of("/chunks/one.ts","/chunks/two.ts"),children);}
        }
    }
    private static int countDecodedFrames(Fixture fixture,String url) throws Exception {
        var player=fixture.manager.createPlayer();
        try {
            AudioTrack track=fixture.load(url);AudioPlaybackPreparation.start(player,track,null);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);int count=0;
            while(System.nanoTime()<deadline){
                var frame=player.provide();
                if(frame!=null)count++;
                else if(player.getPlayingTrack()==null && count>0)return count;
                else Thread.sleep(1);
            }
            fail("Playback did not finish");return 0;
        } finally {player.destroy();}
    }
}
