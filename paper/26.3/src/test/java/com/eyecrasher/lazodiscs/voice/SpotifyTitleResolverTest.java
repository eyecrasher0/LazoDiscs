package com.eyecrasher.lazodiscs.voice;

import com.eyecrasher.lazodiscs.service.TrackMatchScorer;
import com.eyecrasher.lazodiscs.service.TrackMetadata;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpotifyTitleResolverTest {
    private static SpotifyTitleResolver.SpotifyMetadata parse(String method, String document) throws Exception {
        var parser = SpotifyTitleResolver.class.getDeclaredMethod(method, String.class);
        parser.setAccessible(true);
        @SuppressWarnings("unchecked")
        var result = (Optional<SpotifyTitleResolver.SpotifyMetadata>) parser.invoke(null, document);
        return result.orElseThrow();
    }

    @Test void plainOpenGraphTitlesDoNotInventArtistSuffixes() throws Exception {
        for (String title : List.of("Stand by Me", "Love - Live", "Stay With Me", "Love | Spotify")) {
            var metadata = parse("parseMetadataFromHtml", "<meta property='og:title' content='" + title + "'>");
            assertEquals(title, metadata.title());
            assertEquals(List.of(), metadata.artists());
            var expected = new TrackMetadata(metadata.title(), metadata.artists(), metadata.durationMs());
            assertTrue(TrackMatchScorer.matchesTitle(title, expected));
            assertFalse(TrackMatchScorer.matchesTitle(title.split(" ", 2)[0], expected),
                    "A truncated prefix must not become a different accepted recording");
        }
    }

    @Test void structuredNameKeepsItsCompleteTitleWithoutArtistMetadata() throws Exception {
        var metadata = parse("parseMetadataFromHtml",
                "<script type='application/ld+json'>{\"@type\":\"MusicRecording\",\"name\":\"Stand by Me - Live\"}</script>");
        assertEquals("Stand by Me - Live", metadata.title());
        assertTrue(metadata.artists().isEmpty());
    }

    @Test void formattedPageTitleStillSuppliesKnownArtistMetadata() throws Exception {
        var metadata = parse("parseMetadataFromHtml",
                "<title>Stand by Me - song and lyrics by Ben E. King | Spotify</title>");
        assertEquals("Stand by Me", metadata.title());
        assertEquals(List.of("Ben E. King"), metadata.artists());
    }

    @Test void plainOEmbedTitlesPreserveByDashAndBrandWords() throws Exception {
        for (String title : List.of("Stand by Me", "Love - Live", "Love | Spotify")) {
            var metadata = parse("parseMetadataFromOEmbed", "{\"title\":\"" + title + "\"}");
            assertEquals(title, metadata.title());
            assertTrue(metadata.artists().isEmpty());
        }
    }
}
