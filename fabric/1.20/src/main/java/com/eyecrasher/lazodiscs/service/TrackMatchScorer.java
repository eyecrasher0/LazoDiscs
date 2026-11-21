package com.eyecrasher.lazodiscs.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TrackMatchScorer {
    private TrackMatchScorer() {}

    public static boolean matchesTitle(String rawTitle, String expectedTitle) {
        String expected = titleKey(expectedTitle);
        return !expected.isBlank() && titleKey(rawTitle).equals(expected);
    }

    public static boolean matchesTitle(String rawTitle, TrackMetadata metadata) {
        if (metadata == null) return false;
        if (matchesTitle(rawTitle, metadata.title())) return true;
        // A known artist prefix is display metadata, not extra song-title words.
        if (rawTitle == null) return false;
        for (String artist : metadata.artists()) {
            String prefix = "(?iu)^\\s*" + java.util.regex.Pattern.quote(artist) + "\\s+[-–—:]\\s+";
            String title = rawTitle.replaceFirst(prefix, "");
            if (!title.equals(rawTitle) && matchesTitle(title, metadata.title())) return true;
        }
        return false;
    }

    private static String titleKey(String raw) {
        if (raw == null) return "";
        String decoration = "(?:official(?: music)? (?:audio|video)|official visuali[sz]er|"
                + "audio|music video|lyric(?:s| video)?|visuali[sz]er|hd|4k|"
                + "remaster(?:ed)?(?:\\s+\\d{4})?)";
        String title = raw.replaceAll("(?i)\\s*[\\[(]\\s*" + decoration + "\\s*[\\])]", " ")
                .replaceFirst("(?i)\\s+[-–—]\\s+" + decoration + "\\s*$", "")
                .replaceFirst("(?i)\\s+official(?: music)? (?:audio|video)\\s*$", "");
        return title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .replaceAll("\\s+", " ").trim();
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
