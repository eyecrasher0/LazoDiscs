package com.eyecrasher.lazodiscs.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrackMatchScorerTest {
    @Test void differentSongCannotPassEvenWhenTheExpectedTitleIsASubstring() {
        assertFalse(TrackMatchScorer.matchesTitle("Stay With Me", "Stay"));
        assertFalse(TrackMatchScorer.matchesTitle("Let It Go", "Let It Be"));
        assertFalse(TrackMatchScorer.matchesTitle("Be It Let", "Let It Be"));
        assertFalse(TrackMatchScorer.matchesTitle("Stay (With Me)", "Stay"));
        assertFalse(TrackMatchScorer.matchesTitle("Dream Audio", "Dream"));
    }
    @Test void acceptsOnlyRecognizedDisplayDecorationsAndKnownArtistPrefixes() {
        var metadata = new TrackMetadata("Stay", List.of("Fixture Artist"), 180000L);
        assertTrue(TrackMatchScorer.matchesTitle("STAY (Official Audio)", metadata));
        assertTrue(TrackMatchScorer.matchesTitle("Fixture Artist - Stay [Official Music Video]", metadata));
        assertTrue(TrackMatchScorer.matchesTitle("Stay - Remastered 2011", metadata));
        assertFalse(TrackMatchScorer.matchesTitle("Another Artist - Stay", metadata));
        assertFalse(TrackMatchScorer.matchesTitle("Fixture Artist - Stay With Me", metadata));
    }
}
