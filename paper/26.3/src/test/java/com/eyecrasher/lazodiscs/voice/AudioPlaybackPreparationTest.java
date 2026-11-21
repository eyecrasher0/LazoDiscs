package com.eyecrasher.lazodiscs.voice;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.ImmutableAudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import dev.lavalink.youtube.AllClientsFailedException;
import dev.lavalink.youtube.ClientException;
import dev.lavalink.youtube.clients.MWebWithThumbnail;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudioPlaybackPreparationTest {
    private static final byte[] FIRST_FRAME = {3, 7, 11};

    private static final class DelayedTrack extends BaseAudioTrack {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch failLater = new CountDownLatch(1);
        final AtomicBoolean interruptedBeforeFrame = new AtomicBoolean();
        final FriendlyException error;
        final boolean failBeforeFrame;
        DelayedTrack(FriendlyException error, boolean failBeforeFrame) {
            super(new AudioTrackInfo("fixture", "test", 1000, "fixture", false, null));
            this.error = error;
            this.failBeforeFrame = failBeforeFrame;
        }
        @Override public void process(LocalAudioTrackExecutor executor) throws Exception {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException cancelled) { interruptedBeforeFrame.set(true); throw cancelled; }
            if (failBeforeFrame) throw error;
            executor.getAudioBuffer().consume(new ImmutableAudioFrame(0, FIRST_FRAME, 100, StandardAudioDataFormats.DISCORD_OPUS));
            if (error != null) { failLater.await(); throw error; }
            executor.waitOnEnd();
        }
    }

    @Test void waitsForRealFramesAndPreservesTheFirstFrame() throws Exception {
        var manager = new CooperativeAudioPlayerManager();
        AudioPlayer player = manager.createPlayer();
        var track = new DelayedTrack(null, false);
        var returned = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread load = new Thread(() -> {
            try { AudioPlaybackPreparation.start(player, track, null); }
            catch (Throwable error) { failure.set(error); }
            finally { returned.countDown(); }
        });
        try {
            load.start();
            assertTrue(track.entered.await(5, TimeUnit.SECONDS));
            assertFalse(returned.await(50, TimeUnit.MILLISECONDS));
            track.release.countDown();
            assertTrue(returned.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertArrayEquals(FIRST_FRAME, player.provide().getData());
        } finally { track.release.countDown(); player.destroy(); manager.shutdown(); load.join(5000); }
    }

    @Test void interruptedPreparationDisposesOnlyAfterTheDecoderIsReady() throws Exception {
        var manager = new CooperativeAudioPlayerManager();
        AudioPlayer player = manager.createPlayer();
        var track = new DelayedTrack(null, false);
        var returned = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread load = new Thread(() -> {
            try { AudioPlaybackPreparation.start(player, track, null); }
            catch (Throwable error) { failure.set(error); }
            finally { returned.countDown(); }
        });
        try {
            load.start();
            assertTrue(track.entered.await(5, TimeUnit.SECONDS));
            load.interrupt();
            assertFalse(returned.await(50, TimeUnit.MILLISECONDS));
            assertFalse(track.interruptedBeforeFrame.get());
            track.release.countDown();
            assertTrue(returned.await(5, TimeUnit.SECONDS));
            assertInstanceOf(InterruptedException.class, failure.get());
            assertFalse(track.interruptedBeforeFrame.get());
            assertNull(player.getPlayingTrack());
        } finally { track.release.countDown(); player.destroy(); manager.shutdown(); load.join(5000); }
    }

    @Test void earlyDecoderFailureKeepsItsCauseAndDoesNotSendALateNotification() throws Exception {
        var manager = new CooperativeAudioPlayerManager();
        AudioPlayer player = manager.createPlayer();
        var error = new FriendlyException("fixture decoder failed", FriendlyException.Severity.COMMON, new IllegalStateException("original cause"));
        var track = new DelayedTrack(error, true);
        var notification = new AtomicReference<FriendlyException>();
        track.release.countDown();
        try {
            FriendlyException result = assertThrows(FriendlyException.class,
                    () -> AudioPlaybackPreparation.start(player, track, notification::set));
            assertSame(error, result);
            assertSame(error.getCause(), result.getCause());
            assertNull(notification.get());
            assertNull(player.getPlayingTrack());
        } finally { player.destroy(); manager.shutdown(); }
    }

    @Test void decoderFailureAfterPreparationReachesThePlaybackCallback() throws Exception {
        var manager = new CooperativeAudioPlayerManager();
        AudioPlayer player = manager.createPlayer();
        var refusal = new FriendlyException("This video requires login.", FriendlyException.Severity.COMMON, null);
        var error = new FriendlyException("late decoder error", FriendlyException.Severity.SUSPICIOUS,
                new AllClientsFailedException(List.of(new ClientException(refusal.getMessage(), new MWebWithThumbnail(), refusal))));
        var track = new DelayedTrack(error, false);
        var notified = new CountDownLatch(1);
        var notification = new AtomicReference<FriendlyException>();
        track.release.countDown();
        try {
            AudioPlaybackPreparation.start(player, track, failed -> { notification.set(failed); notified.countDown(); });
            assertNull(notification.get());
            track.failLater.countDown();
            assertTrue(notified.await(5, TimeUnit.SECONDS));
            assertSame(error, notification.get());
            assertTrue(YoutubeLoginFailure.matches(notification.get()));
            assertArrayEquals(FIRST_FRAME, player.provide().getData());
        } finally { track.failLater.countDown(); player.destroy(); manager.shutdown(); }
    }

    @Test void cooperativeCancellationReturnsTheLoaderWhileTheDecoderResponseIsStillHeld() throws Exception {
        var manager = new CooperativeAudioPlayerManager();
        AudioPlayer player = manager.createPlayer();
        var track = new DelayedTrack(null, false);
        var cancelled = new AtomicBoolean();
        var returned = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread load = new Thread(() -> {
            try { AudioPlaybackPreparation.start(player, track, null, cancelled::get); }
            catch (Throwable error) { failure.set(error); }
            finally { returned.countDown(); }
        });
        try {
            load.start();assertTrue(track.entered.await(5,TimeUnit.SECONDS));
            cancelled.set(true);
            assertTrue(returned.await(5,TimeUnit.SECONDS));
            assertEquals(1,track.release.getCount(),"The response must remain held until the loader returned");
            assertInstanceOf(java.util.concurrent.CancellationException.class,failure.get());
            assertNull(player.getPlayingTrack());
            assertFalse(track.interruptedBeforeFrame.get());
        } finally { track.release.countDown();player.destroy();manager.shutdown();load.join(5000); }
    }
}
