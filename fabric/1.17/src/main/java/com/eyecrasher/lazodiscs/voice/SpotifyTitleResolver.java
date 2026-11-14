package com.eyecrasher.lazodiscs.voice;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class SpotifyTitleResolver {
    private static final int MAX_REDIRECTS = 5;
    private static final String OPEN_SPOTIFY_HOST = "open.spotify.com";
    private static final Set<String> SPOTIFY_SHORT_HOSTS =
            Set.of("spotify.link", "www.spotify.link", "spotify.app.link", "www.spotify.app.link");
    private static final Set<String> SPOTIFY_OBJECT_TYPES =
            Set.of("track", "album", "playlist", "artist", "show", "episode");

    private SpotifyTitleResolver() {}

    public static boolean looksLikeSpotify(String value) {
        if (value == null || value.isBlank()) return false;
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("spotify:")) return true;

        try {
            URI uri = URI.create(trimmed);
            String host = normalizedHost(uri);
            return isOpenSpotifyHost(host) || isSpotifyShortHost(host);
        } catch (Exception ignored) {
            return lower.contains("open.spotify.com/")
                    || lower.contains("spotify.link/")
                    || lower.contains("spotify.app.link/");
        }
    }

    public static String canonicalize(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return parseSpotifyReference(trimmed).map(SpotifyReference::canonicalUrl).orElse(trimmed);
    }

    public static Optional<String> validateSingleTrack(String value) {
        if (value == null || value.isBlank() || !looksLikeSpotify(value)) return Optional.empty();

        Optional<SpotifyReference> reference = parseSpotifyReference(value);
        if (reference.isPresent()) {
            String type = reference.get().type();
            if (!type.equals("track")) {
                return Optional.of(LazoDiscsText.spotifyTrackOnly());
            }
            return Optional.empty();
        }

        String lower = value.trim().toLowerCase(Locale.ROOT);
        if (lower.startsWith("spotify:") || lower.contains("open.spotify.com/")) {
            return Optional.of(LazoDiscsText.spotifyInvalidTrack());
        }

        return Optional.empty();
    }

    public static Optional<String> resolveTitle(String spotifyUrl) {
        return resolveMetadata(spotifyUrl).map(SpotifyMetadata::searchQuery);
    }

    public static Optional<SpotifyMetadata> resolveMetadata(String spotifyUrl) {
        String canonical = canonicalize(spotifyUrl);
        String resolvedUrl = resolveSpotifyRedirects(canonical).orElse(canonical);
        Optional<String> validationError = validateSingleTrack(resolvedUrl);
        if (validationError.isPresent()) {
            LazoDiscs.LOGGER.debug(
                    "Skipping Spotify metadata resolve for '{}': {}",
                    spotifyUrl,
                    validationError.get());
            return Optional.empty();
        }

        Optional<SpotifyMetadata> fromPage = resolveFromSpotifyPage(resolvedUrl);
        if (fromPage.isPresent()) return fromPage;
        return resolveFromOEmbed(resolvedUrl);
    }

    private static Optional<String> resolveSpotifyRedirects(String spotifyUrl) {
        try {
            URI firstUri = URI.create(spotifyUrl);
            if (!isSpotifyShortHost(normalizedHost(firstUri))) {
                return Optional.of(canonicalize(spotifyUrl));
            }

            String current = spotifyUrl;
            for (int i = 0; i < MAX_REDIRECTS; i++) {
                HttpURLConnection connection =
                        (HttpURLConnection) URI.create(current).toURL().openConnection();
                try {
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(7000);
                    connection.setReadTimeout(7000);
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0 LazoDiscs");
                    connection.setRequestProperty(
                            "Accept",
                            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

                    int code = connection.getResponseCode();
                    if (code < 300 || code >= 400) {
                        return Optional.of(canonicalize(current));
                    }

                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        return Optional.empty();
                    }

                    current = URI.create(current).resolve(location).toString();
                    String canonical = canonicalize(current);
                    if (!canonical.equals(current)
                            || isOpenSpotifyHost(normalizedHost(URI.create(canonical)))) {
                        return Optional.of(canonical);
                    }
                } finally {
                    connection.disconnect();
                }
            }
        } catch (Exception e) {
            LazoDiscs.LOGGER.debug(
                    "Could not resolve Spotify redirect for '{}': {}", spotifyUrl, e.toString());
        }
        return Optional.empty();
    }

    private static Optional<SpotifyReference> parseSpotifyReference(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);

        if (lower.startsWith("spotify:")) {
            String[] parts = trimmed.split(":", 4);
            if (parts.length < 3) return Optional.empty();
            String type = parts[1].toLowerCase(Locale.ROOT);
            String id = cleanSpotifyId(parts[2]);
            if (!SPOTIFY_OBJECT_TYPES.contains(type) || id.isBlank()) return Optional.empty();
            return Optional.of(new SpotifyReference(type, id));
        }

        try {
            URI uri = URI.create(trimmed);
            if (!isOpenSpotifyHost(normalizedHost(uri))) return Optional.empty();

            List<String> segments = pathSegments(uri.getPath());
            int index =
                    !segments.isEmpty()
                                    && segments.get(0).toLowerCase(Locale.ROOT).startsWith("intl-")
                            ? 1
                            : 0;
            if (segments.size() <= index + 1) return Optional.empty();

            String type = segments.get(index).toLowerCase(Locale.ROOT);
            String id = cleanSpotifyId(segments.get(index + 1));
            if (!SPOTIFY_OBJECT_TYPES.contains(type) || id.isBlank()) return Optional.empty();
            return Optional.of(new SpotifyReference(type, id));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static List<String> pathSegments(String path) {
        if (path == null || path.isBlank()) return List.of();
        List<String> segments = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isBlank()) segments.add(part);
        }
        return segments;
    }

    private static String cleanSpotifyId(String value) {
        if (value == null) return "";
        int end = value.length();
        int query = value.indexOf('?');
        int fragment = value.indexOf('#');
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);
        return value.substring(0, end).replaceAll("[^A-Za-z0-9]", "");
    }

    private static String normalizedHost(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private static boolean isOpenSpotifyHost(String host) {
        return OPEN_SPOTIFY_HOST.equals(host);
    }

    private static boolean isSpotifyShortHost(String host) {
        return SPOTIFY_SHORT_HOSTS.contains(host);
    }

    private static Optional<SpotifyMetadata> resolveFromSpotifyPage(String spotifyUrl) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(spotifyUrl).toURL().openConnection();
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 LazoDiscs");
            connection.setRequestProperty(
                    "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300)
                return Optional.empty();

            StringBuilder html = new StringBuilder();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) html.append(line).append('\n');
            }
            return parseMetadataFromHtml(html.toString());
        } catch (Exception e) {
            LazoDiscs.LOGGER.debug(
                    "Could not resolve Spotify page metadata for '{}': {}",
                    spotifyUrl,
                    e.toString());
            return Optional.empty();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Optional<SpotifyMetadata> parseMetadataFromHtml(String html) {
        String title =
                extractMeta(html, "og:title")
                        .or(() -> extractMeta(html, "twitter:title"))
                        .map(SpotifyTitleResolver::cleanPlainText)
                        .orElse(null);
        String description =
                extractMeta(html, "og:description")
                        .or(() -> extractMeta(html, "twitter:description"))
                        .map(SpotifyTitleResolver::cleanPlainText)
                        .orElse("");
        SplitTitle titleTag =
                extractTitleTag(html)
                        .map(SpotifyTitleResolver::stripSpotifyDecorations)
                        .map(SpotifyTitleResolver::splitTitleAndArtist)
                        .orElse(null);
        if ((title == null || title.isBlank()) && titleTag != null) {
            title = titleTag.title();
        }

        List<String> artists = new ArrayList<>();
        Long durationMs =
                extractMeta(html, "music:duration")
                        .map(SpotifyTitleResolver::parseSecondsMillis)
                        .orElse(null);

        extractMeta(html, "music:musician_description")
                .map(SpotifyTitleResolver::cleanPlainText)
                .ifPresent(artist -> addArtists(artists, artist));
        if (artists.isEmpty() && titleTag != null) {
            for (String artist : titleTag.artists()) {
                if (isValidArtistHint(artist, title) && !artists.contains(artist))
                    artists.add(artist);
            }
        }

        String ldJson = extractScriptByType(html, "application/ld+json").orElse("");
        if (!ldJson.isBlank()) {
            String ldTitle = extractJsonStringAfter(ldJson, "\"@type\"", "\"name\"").orElse(null);
            if (ldTitle != null && !ldTitle.isBlank()) title = cleanPlainText(ldTitle);

            Optional<String> artistBlock = substringAfter(ldJson, "\"byArtist\"");
            if (artistBlock.isPresent() && artists.isEmpty()) {
                List<String> parsedArtists = extractAllJsonStrings(artistBlock.get(), "name", 6);
                for (String artist : parsedArtists) {
                    String a = cleanPlainText(artist);
                    if (isValidArtistHint(a, title) && !artists.contains(a)) artists.add(a);
                }
            }

            if (durationMs == null) {
                durationMs =
                        extractJsonString(ldJson, "duration")
                                .map(SpotifyTitleResolver::parseIsoDurationMillis)
                                .orElse(null);
            }
        }

        // Spotify descriptions often contain title, artist, and year chunks separated by
        // middle-dot/bullet characters. Some launchers/logs store mojibake variants.
        if (artists.isEmpty() && !description.isBlank()) {
            parseArtistsFromDescription(description, title)
                    .forEach(
                            a -> {
                                if (!artists.contains(a)) artists.add(a);
                            });
        }

        if (title == null || title.isBlank()) return Optional.empty();
        String normalizedTitle = stripSpotifyDecorations(title);
        if (artists.isEmpty()) {
            SplitTitle split = splitTitleAndArtist(normalizedTitle);
            normalizedTitle = split.title();
            artists.addAll(split.artists());
        }

        if (normalizedTitle.isBlank()) return Optional.empty();
        return Optional.of(new SpotifyMetadata(normalizedTitle, List.copyOf(artists), durationMs));
    }

    private static Optional<SpotifyMetadata> resolveFromOEmbed(String spotifyUrl) {
        HttpURLConnection connection = null;
        try {
            String encoded = URLEncoder.encode(spotifyUrl, StandardCharsets.UTF_8);
            URI uri = URI.create("https://open.spotify.com/oembed?url=" + encoded);
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(10000);
            connection.setRequestProperty("User-Agent", "LazoDiscs");
            if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300)
                return Optional.empty();
            StringBuilder json = new StringBuilder();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) json.append(line);
            }
            String rawTitle = extractJsonString(json.toString(), "title").orElse(null);
            if (rawTitle == null || rawTitle.isBlank()) return Optional.empty();
            SplitTitle split = splitTitleAndArtist(stripSpotifyDecorations(rawTitle));
            return Optional.of(
                    new SpotifyMetadata(split.title(), List.copyOf(split.artists()), null));
        } catch (Exception e) {
            LazoDiscs.LOGGER.debug(
                    "Could not resolve Spotify oEmbed title for '{}': {}",
                    spotifyUrl,
                    e.toString());
            return Optional.empty();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static List<String> parseArtistsFromDescription(String description, String title) {
        String cleaned = normalizeSpotifySeparators(cleanPlainText(description));
        List<String> parts = new ArrayList<>();
        for (String raw : cleaned.split("\\s*\u00B7\\s*")) {
            String part = raw.trim();
            if (!part.isBlank()) parts.add(part);
        }

        List<String> out = new ArrayList<>();
        int markerIndex = -1;
        for (int i = 0; i < parts.size(); i++) {
            if (isSpotifyDescriptionMarker(parts.get(i))) {
                markerIndex = i;
                break;
            }
        }

        if (markerIndex > 0) {
            addArtists(out, parts.get(0), title);
        } else if (markerIndex == 0 && parts.size() > 1) {
            addArtists(out, parts.get(1), title);
        } else {
            for (String part : parts) {
                if (isValidArtistHint(part, title)) {
                    addArtists(out, part, title);
                    if (!out.isEmpty()) break;
                }
            }
        }
        return out;
    }

    private static boolean isSpotifyDescriptionMarker(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.equals("song")
                || lower.equals("album")
                || lower.equals("single")
                || lower.equals("playlist")
                || lower.matches("\\d{4}");
    }

    private static String normalizeSpotifySeparators(String value) {
        return value.replace("\u2022", "\u00B7")
                .replace("\u0432\u0402\u045E", "\u00B7")
                .replace("\u0412\u00B7", "\u00B7");
    }

    private static SplitTitle splitTitleAndArtist(String raw) {
        String t = cleanPlainText(raw);
        List<String> artists = new ArrayList<>();

        String[] patterns = {
            " - song and lyrics by ", " - song by ", " - single by ", " - album by ", " by "
        };
        for (String pattern : patterns) {
            int idx = t.toLowerCase(Locale.ROOT).indexOf(pattern);
            if (idx > 0) {
                String title = t.substring(0, idx).trim();
                String artistPart = t.substring(idx + pattern.length()).trim();
                addArtists(artists, artistPart);
                return new SplitTitle(title, artists);
            }
        }

        int dash = t.lastIndexOf(" - ");
        if (dash > 0) {
            String left = t.substring(0, dash).trim();
            String right = t.substring(dash + 3).trim();
            if (right.length() <= 50 && !right.toLowerCase(Locale.ROOT).contains("spotify")) {
                addArtists(artists, right);
                return new SplitTitle(left, artists);
            }
        }

        return new SplitTitle(t, artists);
    }

    private static void addArtists(List<String> artists, String artistPart) {
        addArtists(artists, artistPart, null);
    }

    private static void addArtists(List<String> artists, String artistPart, String title) {
        String[] pieces = artistPart.split("\\s*(,|&| and | feat\\.? | ft\\.? | x )\\s*");
        for (String piece : pieces) {
            String a = cleanPlainText(piece);
            if (isValidArtistHint(a, title) && !artists.contains(a)) artists.add(a);
            if (artists.size() >= 4) break;
        }
    }

    private static boolean isValidArtistHint(String value, String title) {
        if (value == null) return false;
        String cleaned = cleanPlainText(value);
        if (cleaned.isBlank() || cleaned.length() > 80) return false;
        if (title != null && normalize(cleaned).equals(normalize(title))) return false;
        if (isSpotifyDescriptionMarker(cleaned)) return false;
        return !looksLikeMarketList(cleaned);
    }

    private static boolean looksLikeMarketList(String value) {
        String cleaned = value.trim();
        if (!cleaned.matches("(?i)[A-Z]{2}(\\s+[A-Z]{2}){2,}.*")) return false;
        int twoLetterTokens = 0;
        for (String token : cleaned.split("\\s+")) {
            if (token.matches("(?i)[A-Z]{2}")) twoLetterTokens++;
        }
        return twoLetterTokens >= 3;
    }

    private static String stripSpotifyDecorations(String title) {
        String t = cleanPlainText(title);
        t = t.replace(" | Spotify", "").trim();
        return t.trim();
    }

    private static Optional<String> extractMeta(String html, String property) {
        String[] needles = {
            "property=\"" + property + "\"",
            "name=\"" + property + "\"",
            "property='" + property + "'",
            "name='" + property + "'"
        };
        for (String needle : needles) {
            int pos = html.indexOf(needle);
            if (pos < 0) continue;
            int tagStart = html.lastIndexOf('<', pos);
            int tagEnd = html.indexOf('>', pos);
            if (tagStart < 0 || tagEnd < 0) continue;
            String tag = html.substring(tagStart, tagEnd + 1);
            Optional<String> content = extractAttribute(tag, "content");
            if (content.isPresent()) return content;
        }
        return Optional.empty();
    }

    private static Optional<String> extractTitleTag(String html) {
        int start = html.indexOf("<title>");
        if (start < 0) return Optional.empty();
        int end = html.indexOf("</title>", start);
        if (end < 0) return Optional.empty();
        return Optional.of(cleanPlainText(html.substring(start + "<title>".length(), end)));
    }

    private static Optional<String> extractScriptByType(String html, String type) {
        int typePos = html.indexOf("type=\"" + type + "\"");
        if (typePos < 0) typePos = html.indexOf("type='" + type + "'");
        if (typePos < 0) return Optional.empty();
        int startTagEnd = html.indexOf('>', typePos);
        if (startTagEnd < 0) return Optional.empty();
        int end = html.indexOf("</script>", startTagEnd);
        if (end < 0) return Optional.empty();
        return Optional.of(html.substring(startTagEnd + 1, end));
    }

    private static Optional<String> extractAttribute(String tag, String attr) {
        int pos = tag.indexOf(attr + "=\"");
        char quote = '"';
        if (pos < 0) {
            pos = tag.indexOf(attr + "='");
            quote = '\'';
        }
        if (pos < 0) return Optional.empty();
        int start = tag.indexOf(quote, pos);
        if (start < 0) return Optional.empty();
        int end = tag.indexOf(quote, start + 1);
        if (end < 0) return Optional.empty();
        return Optional.of(unescapeHtml(tag.substring(start + 1, end)));
    }

    private static Optional<String> substringAfter(String value, String needle) {
        int idx = value.indexOf(needle);
        if (idx < 0) return Optional.empty();
        return Optional.of(value.substring(idx + needle.length()));
    }

    private static Optional<String> extractJsonStringAfter(String json, String after, String key) {
        int start = json.indexOf(after);
        if (start < 0) return Optional.empty();
        return extractJsonString(json.substring(start), key.replace("\"", ""));
    }

    private static Optional<String> extractJsonString(String json, String key) {
        String needle = "\"" + key + "\"";
        int keyPos = json.indexOf(needle);
        if (keyPos < 0) return Optional.empty();
        int colon = json.indexOf(':', keyPos + needle.length());
        if (colon < 0) return Optional.empty();
        int quote = json.indexOf('"', colon + 1);
        if (quote < 0) return Optional.empty();
        return readJsonString(json, quote);
    }

    private static List<String> extractAllJsonStrings(String json, String key, int limit) {
        List<String> out = new ArrayList<>();
        String needle = "\"" + key + "\"";
        int from = 0;
        while (out.size() < limit) {
            int keyPos = json.indexOf(needle, from);
            if (keyPos < 0) break;
            int colon = json.indexOf(':', keyPos + needle.length());
            int quote = colon < 0 ? -1 : json.indexOf('"', colon + 1);
            if (quote < 0) break;
            readJsonString(json, quote).ifPresent(out::add);
            from = quote + 1;
        }
        return out;
    }

    private static Optional<String> readJsonString(String json, int openingQuote) {
        StringBuilder out = new StringBuilder();
        boolean escape = false;
        for (int i = openingQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) {
                switch (c) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (i + 4 < json.length()) {
                            String hex = json.substring(i + 1, i + 5);
                            try {
                                out.append((char) Integer.parseInt(hex, 16));
                                i += 4;
                            } catch (NumberFormatException ignored) {
                                out.append("\\u").append(hex);
                                i += 4;
                            }
                        }
                    }
                    default -> out.append(c);
                }
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                return Optional.of(out.toString());
            } else {
                out.append(c);
            }
        }
        return Optional.empty();
    }

    private static Long parseIsoDurationMillis(String iso) {
        try {
            String s = iso.toUpperCase(Locale.ROOT);
            if (!s.startsWith("PT")) return null;
            s = s.substring(2);
            long totalSeconds = 0;
            StringBuilder number = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (Character.isDigit(c)) {
                    number.append(c);
                    continue;
                }
                if (number.isEmpty()) continue;
                long value = Long.parseLong(number.toString());
                number.setLength(0);
                if (c == 'H') totalSeconds += value * 3600;
                else if (c == 'M') totalSeconds += value * 60;
                else if (c == 'S') totalSeconds += value;
            }
            return totalSeconds > 0 ? totalSeconds * 1000L : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Long parseSecondsMillis(String seconds) {
        try {
            long value = Long.parseLong(seconds.trim());
            return value > 0 ? value * 1000L : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String cleanPlainText(String value) {
        return unescapeHtml(value).replaceAll("\\s+", " ").trim();
    }

    private static String unescapeHtml(String value) {
        return value.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("\\[[^]]*]", " ")
                .replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private record SpotifyReference(String type, String id) {
        private String canonicalUrl() {
            return "https://open.spotify.com/" + type + "/" + id;
        }
    }

    private record SplitTitle(String title, List<String> artists) {}

    public record SpotifyMetadata(String title, List<String> artists, Long durationMs) {
        public String searchQuery() {
            StringBuilder query = new StringBuilder(title == null ? "" : title.trim());
            for (String artist : artists) {
                if (artist != null && !artist.isBlank()) query.append(' ').append(artist.trim());
            }
            return query.toString().replaceAll("\\s+", " ").trim();
        }

        public String primaryArtist() {
            return artists.isEmpty() ? "" : artists.get(0);
        }
    }
}
