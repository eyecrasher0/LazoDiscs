package com.eyecrasher.lazodiscs.voice;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.ImmutableAudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CooperativeAudioPlayerManagerTest {
    private static final class Fixture implements AutoCloseable {
        final CooperativeAudioPlayerManager manager = new CooperativeAudioPlayerManager();
        final AtomicInteger externalInterrupts = new AtomicInteger();
        final AudioPlayer player;
        Fixture() {
            manager.setFrameBufferDuration(200);
            ((ThreadPoolExecutor) manager.getExecutor()).setThreadFactory(task -> {
                Thread thread = new Thread(task, "test-decoder") {
                    @Override public void interrupt() {
                        // LavaPlayer restores its own interrupt status only after decoder
                        // cleanup. An outside interruption during decoding is the regression.
                        if (Thread.currentThread() != this) externalInterrupts.incrementAndGet();
                        super.interrupt();
                    }
                };
                thread.setDaemon(true);
                return thread;
            });
            player = manager.createPlayer();
        }
        @Override public void close() { player.destroy(); manager.shutdown(); }
    }

    private abstract static class Track extends BaseAudioTrack {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch ended = new CountDownLatch(1);
        Track() { super(new AudioTrackInfo("fixture", "test", 1000, "fixture", false, null)); }
        void frame(LocalAudioTrackExecutor executor) throws InterruptedException {
            executor.getAudioBuffer().consume(new ImmutableAudioFrame(0, new byte[]{3, 7, 11}, 100,
                    StandardAudioDataFormats.DISCORD_OPUS));
        }
    }

    @Test void stopBeforeFirstFrameDoesNotInterruptTheDecoder() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        Track track = new Track() {
            @Override public void process(LocalAudioTrackExecutor executor) throws Exception {
                entered.countDown();
                try { release.await(); frame(executor); }
                finally { ended.countDown(); }
            }
        };
        try (Fixture fixture = new Fixture()) {
            try {
                fixture.player.playTrack(track);
                assertTrue(track.entered.await(5, TimeUnit.SECONDS));
                fixture.player.stopTrack();
                assertNull(fixture.player.getPlayingTrack());
                assertFalse(track.ended.await(50, TimeUnit.MILLISECONDS));
                assertEquals(0, fixture.externalInterrupts.get());
                release.countDown();
                assertTrue(track.ended.await(5, TimeUnit.SECONDS));
                assertEquals(0, fixture.externalInterrupts.get());
            } finally { release.countDown(); }
        }
    }

    @Test void stopReleasesAFullBufferWithoutInterruptingTheDecoder() throws Exception {
        Track track = new Track() {
            @Override public void process(LocalAudioTrackExecutor executor) throws Exception {
                try {
                    for (int i = 0; i < executor.getAudioBuffer().getFullCapacity(); i++) frame(executor);
                    entered.countDown();
                    frame(executor);
                    executor.waitOnEnd();
                } finally { ended.countDown(); }
            }
        };
        try (Fixture fixture = new Fixture()) {
            fixture.player.playTrack(track);
            assertTrue(track.entered.await(5, TimeUnit.SECONDS));
            assertFalse(track.ended.await(50, TimeUnit.MILLISECONDS));
            fixture.player.stopTrack();
            assertTrue(track.ended.await(5, TimeUnit.SECONDS));
            assertEquals(0, fixture.externalInterrupts.get());
        }
    }

    @Test void stopReleasesADecoderWaitingForTheLastBufferedFrame() throws Exception {
        Track track = new Track() {
            @Override public void process(LocalAudioTrackExecutor executor) throws Exception {
                try { frame(executor); entered.countDown(); executor.waitOnEnd(); }
                finally { ended.countDown(); }
            }
        };
        try (Fixture fixture = new Fixture()) {
            fixture.player.playTrack(track);
            assertTrue(track.entered.await(5, TimeUnit.SECONDS));
            assertFalse(track.ended.await(50, TimeUnit.MILLISECONDS));
            fixture.player.destroy();
            assertTrue(track.ended.await(5, TimeUnit.SECONDS));
            assertEquals(0, fixture.externalInterrupts.get());
        }
    }

    @Test void consumingFramesUnblocksTheProducerAndReachesNaturalEnd() throws Exception {
        Track track = new Track() {
            @Override public void process(LocalAudioTrackExecutor executor) throws Exception {
                try {
                    entered.countDown();
                    for (int i = 0; i < 40; i++) frame(executor);
                    executor.waitOnEnd();
                } finally { ended.countDown(); }
            }
        };
        try (Fixture fixture = new Fixture()) {
            fixture.player.playTrack(track);
            assertTrue(track.entered.await(5, TimeUnit.SECONDS));
            int frames = 0;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (fixture.player.getPlayingTrack() != null && System.nanoTime() < deadline) {
                var frame = fixture.player.provide(100, TimeUnit.MILLISECONDS);
                if (frame != null && !frame.isTerminator()) { assertArrayEquals(new byte[]{3, 7, 11}, frame.getData()); frames++; }
            }
            assertEquals(40, frames);
            assertTrue(track.ended.await(5, TimeUnit.SECONDS));
            assertNull(fixture.player.getPlayingTrack());
            assertEquals(0, fixture.externalInterrupts.get());
        }
    }
}
