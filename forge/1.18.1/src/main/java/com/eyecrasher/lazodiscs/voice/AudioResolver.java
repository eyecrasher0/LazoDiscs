package com.eyecrasher.lazodiscs.voice;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Legacy resolver retained for integrations compiled against the original API. Playback and URL
 * policy are handled by {@link LavaPcmFeeder}.
 */
@Deprecated
public final class AudioResolver {
    private AudioResolver() {}

    public static ResolvedAudio resolve(String rawUrl, AtomicBoolean closed) {
        return ResolvedAudio.url(rawUrl);
    }

    public record ResolvedAudio(String displayName, String url, Path file) {
        public static ResolvedAudio url(String url) {
            return new ResolvedAudio(url, url, null);
        }

        public static ResolvedAudio file(Path file, String originalUrl) {
            return new ResolvedAudio(file.toString(), null, file);
        }

        public boolean isFile() {
            return file != null;
        }
    }
}
