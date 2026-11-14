package com.eyecrasher.lazodiscs.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Filter;
import java.util.logging.LogRecord;

/** Backend adapters cover Minecraft/Paper Log4j and Spigot/legacy audio JUL. */
public final class YoutubeLoginLogFilter implements AutoCloseable {
    static final String[] LOGGERS = {
        "com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor",
        "com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager"
    };
    private final Consumer<String> warning;
    private final List<Runnable> detach = new ArrayList<Runnable>();
    private boolean installed;

    public YoutubeLoginLogFilter(Consumer<String> warning) { this.warning = warning; }

    public synchronized void install() {
        if (installed) return;
        installed = true;
        for (String name : LOGGERS) {
            attach(java.util.logging.Logger.getLogger(name));
        }
        try {
            Class.forName("org.apache.logging.log4j.core.Logger", false, getClass().getClassLoader());
            Log4j.attach(this);
        } catch (ClassNotFoundException | NoClassDefFoundError unavailable) {
            // Spigot does not have to provide Log4j; its SLF4J binding uses JUL.
        }
    }

    boolean replace(Throwable thrown, String context) {
        // Class identity keeps exceptions from unrelated plugin copies out of this filter.
        if (!YoutubeLoginFailure.matches(thrown)) return false;
        try { warning.accept(YoutubeLoginFailure.shortWarning(context)); return true; }
        catch (RuntimeException failedWarning) { return false; }
    }

    synchronized void attach(final java.util.logging.Logger logger) {
        final Filter previous = logger.getFilter();
        final Filter filter = new Filter() {
            @Override public boolean isLoggable(LogRecord record) {
                if (replace(record.getThrown(), record.getMessage())) return false;
                return previous == null || previous.isLoggable(record);
            }
        };
        logger.setFilter(filter);
        detach.add(() -> { if (logger.getFilter() == filter) logger.setFilter(previous); });
    }

    synchronized void attachLog4j(Object logger) {
        Log4j.attachLogger(this, logger);
    }

    @Override public synchronized void close() {
        for (Runnable action : detach) action.run();
        detach.clear();
        installed = false;
    }

    private static final class Log4j {
        static void attachLogger(YoutubeLoginLogFilter owner, Object instance) {
            final org.apache.logging.log4j.core.Logger logger = (org.apache.logging.log4j.core.Logger) instance;
            final org.apache.logging.log4j.core.filter.AbstractFilter filter = new org.apache.logging.log4j.core.filter.AbstractFilter() {
                @Override public Result filter(org.apache.logging.log4j.core.LogEvent event) {
                    return owner.replace(event.getThrown(), event.getMessage() == null ? null : event.getMessage().getFormattedMessage())
                        ? Result.DENY : Result.NEUTRAL;
                }
            };
            logger.addFilter(filter);
            owner.detach.add(() -> logger.getContext().getConfiguration().getLoggerConfig(logger.getName()).removeFilter(filter));
        }
        static void attach(YoutubeLoginLogFilter owner) {
            for (String name : LOGGERS) {
                Object logger = org.apache.logging.log4j.LogManager.getLogger(name);
                if (logger instanceof org.apache.logging.log4j.core.Logger) owner.attachLog4j(logger);
            }
        }
    }
}
