package com.eyecrasher.lazodiscs.voice;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import dev.lavalink.youtube.AllClientsFailedException;
import dev.lavalink.youtube.ClientException;
import dev.lavalink.youtube.clients.MWebWithThumbnail;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
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
            // A language override must not replace the mandated English YouTube reason.
            Files.writeString(directory.resolve("lang/en_us.toml"), "command.burn_failed = \"Could not burn disc\"\naudio.youtube_login_required = \"Custom translated refusal\"\n");
            assertEquals("YouTube requires sign-in. Please try another link.",
                    LazoDiscsVoiceAddon.messageOf(refused()));
            assertEquals("YouTube requires sign-in. Please try another link.",
                    LazoDiscsVoiceAddon.messageOf(new RuntimeException("load failed", refused())));
            assertEquals("Connection reset", LazoDiscsVoiceAddon.messageOf(new IOException("Connection reset")));
            Files.writeString(directory.resolve("lang/en_us.toml"),
                    "command.burn_failed = \"Custom burn failure\"\n"
                    + "audio.load_failed = \"Custom playback failure\"\n"
                    + "search.failed = \"Custom search failure\"\n");
            com.eyecrasher.lazodiscs.text.LazoDiscsText.reload();
            var plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
            var reason = YoutubeLoginFailure.PLAYER_MESSAGE;
            assertEquals(reason, plain.serialize(com.eyecrasher.lazodiscs.text.LazoDiscsText.burnFailed(reason)));
            assertEquals(reason, plain.serialize(com.eyecrasher.lazodiscs.text.LazoDiscsText.audioLoadFailed("Fixture", reason)));
            assertEquals(reason, plain.serialize(com.eyecrasher.lazodiscs.text.LazoDiscsText.searchFailed(reason)));
            assertEquals("Custom burn failure", plain.serialize(com.eyecrasher.lazodiscs.text.LazoDiscsText.burnFailed("Connection reset")));

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

    @Test void ownedLoggersReplaceOnlyKnownStacksAndLeaveHostLoggersAlone() {
        List<String> shortWarnings = new ArrayList<>();
        List<LogEvent> events = new ArrayList<>();
        var host = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger(
                "com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor");
        var previousLevel = host.getLevel();
        boolean previousAdditive = host.isAdditive();
        var appender = new AbstractAppender("capture", null, PatternLayout.createDefaultLayout(), false) {
            @Override public void append(LogEvent event) { events.add(event.toImmutable()); }
        };
        appender.start();
        host.addAppender(appender);
        host.setLevel(Level.ALL);
        host.setAdditive(false);
        try {
            var log4j = AudioLogs.getLog4jLogger(host.getName());
            var slf4j = AudioLogs.getLogger(host.getName());
            try (var filter = new YoutubeLoginLogFilter(shortWarnings::add)) {
                filter.install();
                log4j.warn("Suspicious exception for playback of _fMQnR0YApg", refused());
                slf4j.warn("Suspicious exception for playback of _fMQnR0YApg", refused());
                assertEquals(List.of(
                        "YouTube requires sign-in (playback of _fMQnR0YApg). Try another link.",
                        "YouTube requires sign-in (playback of _fMQnR0YApg). Try another link."), shortWarnings);
                assertTrue(events.isEmpty(), "Known sign-in stacks must not reach appenders");

                var unexpected = new IOException("Unexpected decoder/network failure");
                unexpected.setStackTrace(new StackTraceElement[]{
                        new StackTraceElement("LazoDiscs.jar",null,null,"example.Audio","read","Audio.java",42)});
                slf4j.warn("Unexpected audio error", unexpected);
                assertEquals(1, events.size());
                assertSame(unexpected, events.get(0).getThrown());

                var mixed = new AllClientsFailedException(List.of(
                        client(reason("This video requires login.")), client(new IOException("Connection reset"))));
                log4j.warn("Mixed audio error", mixed);
                assertEquals(2, events.size());
                assertSame(mixed, events.get(1).getThrown());

                // An identical logger name used outside this plugin's wrapper remains untouched.
                var otherPlugin = refused();
                host.warn("Another plugin's playback", otherPlugin);
                assertSame(otherPlugin, events.get(2).getThrown());
                assertEquals(2, shortWarnings.size());
            }
            slf4j.warn("After plugin shutdown", refused());
            assertEquals(4, events.size());
            assertEquals(2, shortWarnings.size());
        } finally {
            host.removeAppender(appender);
            host.setLevel(previousLevel);
            host.setAdditive(previousAdditive);
            appender.stop();
        }
    }

    @Test void ownedLoggersRedactMediaUrlsWithoutMutatingOriginalErrors() {
        var events = new ArrayList<LogEvent>();
        String url = "https://o'brien:secret@media.example/private-token/song.wav?token=signed-secret#fragment";
        var original = new IOException("Network failure for " + url, new IllegalStateException("decoder details"));
        var host = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger("LazoDiscs.test.urls");
        var previousLevel = host.getLevel();
        boolean previousAdditive = host.isAdditive();
        var appender = new AbstractAppender("url-capture", null, PatternLayout.createDefaultLayout(), false) {
            @Override public void append(LogEvent event) { events.add(event.toImmutable()); }
        };
        appender.start();
        host.addAppender(appender);
        host.setLevel(Level.ALL);
        host.setAdditive(false);
        try {
            AudioLogs.getLogger(host.getName()).warn("Could not load {}", url, original);
            AudioLogs.getLog4jLogger(host.getName()).error("Could not load " + url, original);
            assertEquals(2, events.size());
            for (var event : events) {
                String rendered = event.getMessage().getFormattedMessage();
                assertFalse(rendered.contains("signed-secret"));
                assertFalse(rendered.contains("user:secret"));
                assertFalse(rendered.contains("private-token"));
                assertTrue(rendered.contains("https://media.example/…"));
                assertTrue(rendered.contains("java.io.IOException: Network failure"));
                assertTrue(rendered.contains("Caused by: java.lang.IllegalStateException: decoder details"));
                assertTrue(rendered.contains("YoutubeLoginFailureTest.java:"), "The unrelated stack remains available");
                assertNull(event.getThrown(), "The original secret-bearing throwable must not be rendered again");
            }
            assertEquals(Level.WARN, events.get(0).getLevel());
            assertEquals(Level.ERROR, events.get(1).getLevel());
            assertEquals("Network failure for " + url, original.getMessage());
        } finally {
            host.removeAppender(appender);
            host.setLevel(previousLevel);
            host.setAdditive(previousAdditive);
            appender.stop();
        }
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

    @Test void malformedRelativeRedirectsAreRedactedWithoutChangingTheirCauses() {
        var events = new ArrayList<LogEvent>();
        var host = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger("LazoDiscs.test.redirects");
        var level = host.getLevel();
        boolean additive = host.isAdditive();
        var appender = new AbstractAppender("redirect-capture", null, PatternLayout.createDefaultLayout(), false) {
            @Override public void append(LogEvent event) { events.add(event.toImmutable()); }
        };
        appender.start();host.addAppender(appender);host.setLevel(Level.ALL);host.setAdditive(false);
        try {
            for (String input : List.of("//user:password-secret@media.example/path-secret?token=query-secret bad",
                    "/path-secret?token=query-secret bad")) {
                var syntax = new java.net.URISyntaxException(input,"Invalid redirect",5);
                var original = new IllegalArgumentException("Invalid media redirect: " + input,syntax);
                AudioLogs.getLogger(host.getName()).warn("Could not follow {}", input,original);
                AudioLogs.getLog4jLogger(host.getName()).warn("Could not follow " + input,original);
                assertEquals(input,syntax.getInput());
                assertEquals("Invalid media redirect: " + input,original.getMessage());
            }
            assertEquals(4,events.size());
            for (var event : events) {
                String text = event.getMessage().getFormattedMessage();
                assertFalse(text.contains("password-secret"));assertFalse(text.contains("path-secret"));assertFalse(text.contains("query-secret"));
                assertTrue(text.contains("java.net.URISyntaxException: Invalid redirect at index 5"));
                assertTrue(text.contains("YoutubeLoginFailureTest.java:"));assertNull(event.getThrown());
            }
        } finally { host.removeAppender(appender);host.setLevel(level);host.setAdditive(additive);appender.stop(); }
    }

}
