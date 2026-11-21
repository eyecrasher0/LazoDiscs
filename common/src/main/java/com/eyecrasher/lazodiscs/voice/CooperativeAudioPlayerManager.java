package com.eyecrasher.lazodiscs.voice;

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerOptions;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.TrackStateListener;
import com.sedmelluq.discord.lavaplayer.track.playback.AllocatingAudioFrameBuffer;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrameRebuilder;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Stops local decoders at buffer boundaries without interrupting Forge's shared archive reads. */
public final class CooperativeAudioPlayerManager extends DefaultAudioPlayerManager {
    @Override
    public void executeTrack(TrackStateListener listener, InternalAudioTrack track,
                             AudioConfiguration configuration, AudioPlayerOptions options) {
        AudioTrackExecutor executor = track.createLocalExecutor(this);
        if (executor == null) {
            Integer duration = options.frameBufferDuration.get();
            executor = new CooperativeExecutor(track, new BufferConfiguration(configuration), options,
                    isUsingSeekGhosting(), duration == null ? getFrameBufferDuration() : duration);
        }
        track.assignExecutor(executor, true);
        AudioTrackExecutor playback = executor;
        getExecutor().execute(() -> playback.execute(listener));
    }

    private static final class BufferConfiguration {
        final AudioConfiguration configuration;
        CancellationBuffer buffer;

        BufferConfiguration(AudioConfiguration original) {
            configuration = original.copy();
            configuration.setFrameBufferFactory((duration, format, stopping) ->
                    buffer = new CancellationBuffer(duration, format, stopping));
        }
    }

    private static final class CooperativeExecutor extends LocalAudioTrackExecutor {
        private final CancellationBuffer buffer;

        CooperativeExecutor(InternalAudioTrack track, BufferConfiguration buffers,
                            AudioPlayerOptions options, boolean ghosting, int duration) {
            super(track, buffers.configuration, options, ghosting, duration);
            buffer = buffers.buffer;
        }

        @Override public void stop() { buffer.cancel(); }
    }

    private static final class CancellationBuffer extends AllocatingAudioFrameBuffer {
        // This is LavaPlayer's own executor disposal flag, supplied by its buffer factory.
        private final AtomicBoolean stopping;

        CancellationBuffer(int duration, AudioDataFormat format, AtomicBoolean stopping) {
            super(duration, format, stopping);
            this.stopping = stopping;
        }

        void cancel() {
            synchronized (synchronizer) {
                stopping.set(true);
                super.clear();
                synchronizer.notifyAll();
            }
        }

        @Override public void consume(AudioFrame frame) throws InterruptedException {
            synchronized (synchronizer) {
                // One decoder produces these frames. Reserve capacity under the same
                // monitor as cancellation, so the underlying put cannot block on a full
                // queue and never needs Thread.interrupt to release it.
                while (!stopping.get() && getRemainingCapacity() == 0 && !hasClearOnInsert()) synchronizer.wait();
                if (stopping.get()) throw new InterruptedException("Audio playback was stopped");
                super.consume(frame);
            }
        }

        @Override public void waitForTermination() throws InterruptedException {
            synchronized (synchronizer) {
                while (!terminated && !stopping.get()) synchronizer.wait();
                if (stopping.get()) throw new InterruptedException("Audio playback was stopped");
            }
        }

        @Override public AudioFrame provide() {
            synchronized (synchronizer) {
                AudioFrame frame = super.provide();
                synchronizer.notifyAll();
                return frame;
            }
        }

        @Override public AudioFrame provide(long timeout, TimeUnit unit)
                throws TimeoutException, InterruptedException {
            long remaining = unit.toNanos(timeout);
            long deadline = System.nanoTime() + remaining;
            synchronized (synchronizer) {
                AudioFrame frame;
                while ((frame = provide()) == null && !stopping.get() && remaining > 0) {
                    TimeUnit.NANOSECONDS.timedWait(synchronizer, remaining);
                    remaining = deadline - System.nanoTime();
                }
                return frame;
            }
        }

        @Override public boolean provide(MutableAudioFrame target) {
            synchronized (synchronizer) {
                boolean provided = super.provide(target);
                synchronizer.notifyAll();
                return provided;
            }
        }

        @Override public boolean provide(MutableAudioFrame target, long timeout, TimeUnit unit)
                throws TimeoutException, InterruptedException {
            // AllocatingAudioFrameBuffer bridges this call through our timed provide.
            synchronized (synchronizer) { return super.provide(target, timeout, unit); }
        }

        @Override public void clear() {
            synchronized (synchronizer) {
                super.clear();
                synchronizer.notifyAll();
            }
        }

        @Override public void rebuild(AudioFrameRebuilder rebuilder) {
            synchronized (synchronizer) {
                super.rebuild(rebuilder);
                synchronizer.notifyAll();
            }
        }
    }
}
