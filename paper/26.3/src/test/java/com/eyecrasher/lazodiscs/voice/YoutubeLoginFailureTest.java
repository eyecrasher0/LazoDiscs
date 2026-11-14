package com.eyecrasher.lazodiscs.voice;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import dev.lavalink.youtube.AllClientsFailedException;
import dev.lavalink.youtube.ClientException;
import dev.lavalink.youtube.clients.MWebWithThumbnail;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.DefaultConfiguration;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class YoutubeLoginFailureTest {
    private static ClientException client(Throwable cause) {
        return new ClientException(cause.getMessage(), new MWebWithThumbnail(), cause);
    }

    private static FriendlyException reason(String message) {
        return new FriendlyException(message, FriendlyException.Severity.COMMON, null);
    }

    private static AllClientsFailedException refused() {
        return new AllClientsFailedException(List.of(
                client(reason("This video requires login.")),
                client(reason("This video is unavailable"))));
    }

    @Test void recognizesRealFallbackAndWrappedLoadFailure() {
        assertTrue(YoutubeLoginFailure.matches(refused()));
        assertTrue(YoutubeLoginFailure.matches(new RuntimeException("load failed", refused())));
        assertTrue(YoutubeLoginFailure.matches(new AllClientsFailedException(
                List.of(client(reason("Please sign in"))))));
    }

    @Test void playerMessageIsReadableAndUnknownMessagesStayIntact(@TempDir Path directory) throws Exception {
        var field = LazoDiscsConfig.class.getDeclaredField("directory");
        field.setAccessible(true);
        Object previous = field.get(null);
        field.set(null, directory);
        try {
            Files.createDirectories(directory.resolve("lang"));
            // Existing server language files do not contain the new key yet.
            Files.writeString(directory.resolve("lang/en_us.toml"), "command.burn_failed = \"Could not burn disc: %reason%\"\n");
            assertEquals("YouTube requires sign-in. Please try another link.",
                    LazoDiscsVoiceAddon.messageOf(refused()));
            assertEquals("YouTube requires sign-in. Please try another link.",
                    LazoDiscsVoiceAddon.messageOf(new RuntimeException("load failed", refused())));
            assertEquals("Connection reset", LazoDiscsVoiceAddon.messageOf(new IOException("Connection reset")));
        } finally {
            field.set(null, previous);
        }
    }

    @Test void preservesUnknownMixedUnavailableAndNonYoutubeErrors() {
        assertFalse(YoutubeLoginFailure.matches(new AllClientsFailedException(List.of(
                client(reason("This video requires login.")), client(new IOException("Connection reset"))))));
        assertFalse(YoutubeLoginFailure.matches(new AllClientsFailedException(List.of(
                client(reason("This video requires login.")), client(reason("Could not find cipher"))))));
        assertFalse(YoutubeLoginFailure.matches(new AllClientsFailedException(
                List.of(client(reason("This video is unavailable"))))));
        assertFalse(YoutubeLoginFailure.matches(reason("This video requires login.")));
        assertFalse(YoutubeLoginFailure.matches(new AllClientsFailedException(
                List.of(client(new IllegalStateException("This video requires login."))))));
        assertFalse(YoutubeLoginFailure.matches(new AllClientsFailedException(List.of())));
        assertFalse(YoutubeLoginFailure.matches(null));
    }

    @Test void handlesCyclicExceptionChains() {
        RuntimeException a = new RuntimeException("a");
        RuntimeException b = new RuntimeException("b");
        a.initCause(b);
        b.initCause(a);
        assertFalse(YoutubeLoginFailure.matches(a));
    }

    @Test void realLog4jPipelineReplacesOnlyKnownStackAndDetachesCleanly() {
        List<String> shortWarnings = new ArrayList<>();
        List<LogEvent> originalEvents = new ArrayList<>();
        try (LoggerContext context = new LoggerContext("lazodiscs-error-test")) {
            var configuration = new DefaultConfiguration();
            configuration.getRootLogger().setLevel(Level.ALL);
            context.start(configuration);
            var logger = context.getLogger("com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor");
            logger.setLevel(Level.ALL);
            logger.setAdditive(false);
            var appender = new AbstractAppender("capture", null, PatternLayout.createDefaultLayout(), false) {
                @Override public void append(LogEvent event) { originalEvents.add(event.toImmutable()); }
            };
            appender.start();
            logger.addAppender(appender);
            try (var filter = new YoutubeLoginLogFilter(shortWarnings::add)) {
                filter.attachLog4j(logger);
                logger.warn("Suspicious exception for playback of _fMQnR0YApg", refused());
                assertEquals(1, shortWarnings.size());
                assertEquals("YouTube requires sign-in (playback of _fMQnR0YApg). Try another link.", shortWarnings.get(0));
                assertTrue(originalEvents.isEmpty(), "Known sign-in stack must not reach appenders");

                var unexpected = new IOException("Unexpected decoder/network failure");
                logger.warn("Unexpected audio error", unexpected);
                assertEquals(1, originalEvents.size());
                assertSame(unexpected, originalEvents.get(0).getThrown());

                var mixed = new AllClientsFailedException(List.of(
                        client(reason("This video requires login.")), client(new IOException("Connection reset"))));
                logger.warn("Mixed audio error", mixed);
                assertEquals(2, originalEvents.size());
                assertSame(mixed, originalEvents.get(originalEvents.size() - 1).getThrown());
                assertEquals(1, shortWarnings.size());
            }
            logger.warn("After plugin shutdown", refused());
            assertEquals(3, originalEvents.size(), "Plugin shutdown must remove its filter");
            assertEquals(1, shortWarnings.size());
        }
    }
    @Test void julReplacesOnlyKnownFailuresAndRestoresPreviousFilter() {
        var logger = java.util.logging.Logger.getLogger("LazoDiscs.test.audio");
        var previous = logger.getFilter();
        var events = new ArrayList<java.util.logging.LogRecord>();
        var warnings = new ArrayList<String>();
        var handler = new java.util.logging.Handler() {
            public void publish(java.util.logging.LogRecord record) { events.add(record); }
            public void flush() {}
            public void close() {}
        };
        logger.setUseParentHandlers(false);
        logger.addHandler(handler);
        try (var filter = new YoutubeLoginLogFilter(warnings::add)) {
            filter.attach(logger);
            logger.log(java.util.logging.Level.WARNING, "Suspicious exception for playback", refused());
            assertEquals(1, warnings.size());
            assertTrue(events.isEmpty());
            var unknown = new IOException("Connection reset");
            logger.log(java.util.logging.Level.WARNING, "Unknown", unknown);
            assertEquals(1, events.size());
            assertSame(unknown, events.get(0).getThrown());
        } finally { logger.removeHandler(handler); }
        assertSame(previous, logger.getFilter());
    }

    @Test void unrelatedPluginCopyWithSameClassNameIsNotFiltered() throws Exception {
        String name = AllClientsFailedException.class.getName();
        java.net.URL origin = AllClientsFailedException.class.getProtectionDomain().getCodeSource().getLocation();
        try (var copy = new java.net.URLClassLoader(new java.net.URL[]{origin}, getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String candidate, boolean resolve) throws ClassNotFoundException {
                if (!candidate.equals(name)) return super.loadClass(candidate, resolve);
                synchronized (getClassLoadingLock(candidate)) {
                    Class<?> loaded = findLoadedClass(candidate);
                    if (loaded == null) loaded = findClass(candidate);
                    if (resolve) resolveClass(loaded);
                    return loaded;
                }
            }
        }) {
            var foreign = (Throwable) copy.loadClass(name).getConstructor(List.class).newInstance(
                    List.of(client(reason("This video requires login."))));
            assertEquals(name, foreign.getClass().getName());
            assertFalse(YoutubeLoginFailure.matches(foreign), "The filter must not hide another plugin's exception");
        }
    }

}
