package com.eyecrasher.lazodiscs.voice;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioTrackExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Prepares a decoder before handing a cancellable playback to a source. */
public final class AudioPlaybackPreparation {
    private AudioPlaybackPreparation() {}

    public static void start(AudioPlayer player, AudioTrack track,
                             Consumer<FriendlyException> playbackFailure) throws InterruptedException {
        Object gate = new Object();
        FriendlyException[] failure = {null};
        boolean[] ready = {false};
        AudioEventAdapter listener = new AudioEventAdapter() {
            @Override public void onTrackException(AudioPlayer source, AudioTrack failedTrack, FriendlyException error) {
                boolean notify;
                synchronized (gate) {
                    failure[0] = error;
                    notify = ready[0];
                }
                if (notify && playbackFailure != null) {
                    try { playbackFailure.accept(error); }
                    catch (RuntimeException notificationError) {
                        com.eyecrasher.lazodiscs.LazoDiscs.LOGGER.warn("Could not report audio playback failure", notificationError);
                    }
                }
            }
        };
        player.addListener(listener);
        boolean prepared = false;
        boolean interrupted = false;
        try {
            player.playTrack(track);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (true) {
                synchronized (gate) {
                    AudioTrackExecutor executor = track instanceof InternalAudioTrack
                            ? ((InternalAudioTrack) track).getActiveExecutor() : null;
                    if (failure[0] == null && executor != null && executor.getAudioBuffer().hasReceivedFrames()) {
                        // Do not consume the first frame: callers still use the original AudioPlayer API.
                        ready[0] = true;
                        prepared = true;
                        break;
                    }
                    if (track.getState() == AudioTrackState.FINISHED) {
                        if (failure[0] != null) throw failure[0];
                        throw new FriendlyException("The track produced no audio frames.", FriendlyException.Severity.COMMON, null);
                    }
                }
                if (System.nanoTime() >= deadline) {
                    throw new FriendlyException("Timed out preparing audio playback.", FriendlyException.Severity.SUSPICIOUS,
                            new TimeoutException("The decoder did not produce its first frame within the preparation deadline"));
                }
                try { Thread.sleep(5); }
                catch (InterruptedException cancelled) {
                    // Stopping LavaPlayer interrupts its decoder. On old Forge, interrupting
                    // a first class read closes the mod's shared archive for every thread.
                    // Finish bounded preparation before disposing an interrupted load.
                    interrupted = true;
                }
            }
            if (interrupted) throw new InterruptedException("Audio preparation was interrupted");
        } finally {
            if (!prepared || interrupted) {
                player.removeListener(listener);
                player.destroy();
            } else if (playbackFailure == null) {
                player.removeListener(listener);
            }
        }
    }
}
