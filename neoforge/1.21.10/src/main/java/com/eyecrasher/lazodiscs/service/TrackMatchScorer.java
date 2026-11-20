package com.eyecrasher.lazodiscs.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TrackMatchScorer {
    private TrackMatchScorer() {}

    public static boolean matchesTitle(String rawTitle, String expectedTitle) {
        if (expectedTitle == null) return false;
        // Spotify adds release/version labels after the song name in some titles.
        String expected =
                normalize(
                        expectedTitle.replaceFirst(
                                "(?i)\\s+-\\s+(?:instrumental|sped up|slowed|bass boosted|remix|"
                                        + "remaster(?:ed)?|live|acoustic|radio edit|original mix|"
                                        + "karaoke|nightcore|extended|8d)(?:\\s|$).*",
                                ""));
        String actual = normalize(rawTitle);
        if (expected.isBlank() || actual.isBlank()) return false;
        if ((" " + actual + " ").contains(" " + expected + " ")) return true;
        List<String> words = meaningfulWords(expected);
        return !words.isEmpty() && List.of(actual.split(" ")).containsAll(words);
    }

    public static int score(
            String rawTitle, String rawAuthor, long lengthMs, TrackMetadata metadata) {
        if (metadata == null) return 0;

        String hay = normalize(rawTitle + " " + rawAuthor);
        String titleNorm = normalize(metadata.title());
        int score = 0;

        if (!titleNorm.isBlank()) {
            if (hay.contains(titleNorm)) score += 120;
            List<String> words = meaningfulWords(titleNorm);
            for (String word : words) {
                if (hay.contains(word)) score += 16;
                else score -= 18;
            }
        }

        for (String artist : metadata.artists()) {
            String artistNorm = normalize(artist);
            if (artistNorm.isBlank()) continue;
            if (hay.contains(artistNorm)) score += 95;
            for (String word : meaningfulWords(artistNorm)) {
                if (hay.contains(word)) score += 12;
                else score -= 10;
            }
        }

        Long expectedDuration = metadata.durationMs();
        if (expectedDuration != null && expectedDuration > 0 && lengthMs > 0) {
            long diff = Math.abs(lengthMs - expectedDuration);
            if (diff <= 3000) score += 110;
            else if (diff <= 10_000) score += 80;
            else if (diff <= 25_000) score += 35;
            else score -= (int) Math.min(120, diff / 1000L);
        }

        List<String> penalties =
                List.of(
                        "cover",
                        "remix",
                        "sped up",
                        "slowed",
                        "nightcore",
                        "karaoke",
                        "instrumental",
                        "8d",
                        "loop",
                        "extended",
                        "live",
                        "reaction");
        for (String penalty : penalties) {
            if (hay.contains(penalty) && !titleNorm.contains(penalty)) score -= 45;
        }

        if (hay.contains("official audio")
                || hay.contains("topic")
                || hay.contains("provided to youtube")) score += 15;
        if (hay.contains("lyrics") && !titleNorm.contains("lyrics")) score -= 10;
        return score;
    }

    public static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("\\[[^]]*]", " ")
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static List<String> meaningfulWords(String normalized) {
        if (normalized == null || normalized.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String word : normalized.split(" ")) {
            if (word.length() < 3) continue;
            if (word.equals("the")
                    || word.equals("and")
                    || word.equals("feat")
                    || word.equals("ft")
                    || word.equals("official")
                    || word.equals("audio")) continue;
            out.add(word);
        }
        return out;
    }
}
