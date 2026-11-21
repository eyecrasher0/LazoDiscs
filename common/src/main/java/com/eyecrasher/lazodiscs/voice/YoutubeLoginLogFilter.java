package com.eyecrasher.lazodiscs.voice;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class YoutubeLoginLogFilter implements AutoCloseable {
    private static final AtomicReference<YoutubeLoginLogFilter> ACTIVE = new AtomicReference<>();
    private final Consumer<String> warning;

    public YoutubeLoginLogFilter(Consumer<String> warning) { this.warning = warning; }

    public void install() { ACTIVE.set(this); }

    static boolean replace(String logger, Throwable thrown, String context) {
        YoutubeLoginLogFilter filter = ACTIVE.get();
        if (filter == null || thrown == null) return false;
        if (!logger.equals("com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor")
                && !logger.equals("com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager")) return false;
        if (!YoutubeLoginFailure.matches(thrown)) return false;
        try {
            filter.warning.accept(YoutubeLoginFailure.shortWarning(context));
            return true;
        } catch (RuntimeException failedWarning) {
            return false;
        }
    }

    @Override public void close() { ACTIVE.compareAndSet(this, null); }
}
