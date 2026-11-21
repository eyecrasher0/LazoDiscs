package com.eyecrasher.lazodiscs.voice;

import com.eyecrasher.lazodiscs.data.CustomDiscData;
import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A bounded 20ms sender shared by the older Voice APIs and the optional Bukkit adapter. */
public final class StreamingSession implements AutoCloseable {
    public interface Sink extends AutoCloseable {
        void frame(byte[] opus, long sequence) throws Exception;
        default void finish() { close(); }
        void close();
    }
    private static final Set<StreamingSession> ACTIVE = ConcurrentHashMap.newKeySet();
    private static ScheduledExecutorService clock;
    private final ScheduledExecutorService scheduler;
    private final Sink sink;
    private final Consumer<String> finished;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile LavaPcmFeeder.StreamingPlayback playback;
    private volatile Future<?> load, sender;
    private volatile String failure;
    private String title;
    private long sequence, lastFrame;

    public StreamingSession(Sink sink, Consumer<String> finished) {
        this.sink = sink;
        this.finished = finished;
        synchronized (StreamingSession.class) {
            scheduler = clock;
            if (scheduler != null) ACTIVE.add(this);
        }
        if (scheduler == null) close();
    }

    public static synchronized void enable() {
        if (clock == null) clock = Executors.newScheduledThreadPool(2, task -> {
            Thread worker = new Thread(task, "LazoDiscs-Streaming"); worker.setDaemon(true); return worker;
        });
    }

    public void start(CustomDiscData disc) {
        if (closed.get()) return;
        title = disc.title();
        load = AudioLoadExecutor.submit(() -> {
            try {
                if (closed.get()) return;
                var opened = LavaPcmFeeder.openStream(disc.url(), disc.title(), disc.volume(),
                        error -> { if (!closed.get()) failure = messageOf(error); });
                synchronized (this) {
                    if (closed.get()) { opened.close(); return; }
                    playback = opened;
                    opened.player().addListener(new AudioEventAdapter() {
                        @Override public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException error) { failure = messageOf(error); }
                        @Override public void onTrackStuck(AudioPlayer player, AudioTrack track, long threshold) { failure = LazoDiscsText.trackStuck(threshold); }
                    });
                    lastFrame = System.nanoTime();
                    sender = scheduler.scheduleAtFixedRate(this::tick, 0, 20, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                if (!closed.get()) finish("Audio loading interrupted", false);
            } catch (Exception | LinkageError error) { fail(error); }
        }, () -> finish(LazoDiscsText.audioLoadBusy(), false));
        if (closed.get()) load.cancel(false);
    }
    private void tick() {
        if (closed.get()) return;
        try {
            var opened = playback;
            var frame = opened.player().provide();
            if (closed.get()) return;
            if (frame != null) { sink.frame(frame.getData(), sequence++); lastFrame = System.nanoTime(); return; }
            if (failure != null) { finish(failure, false); return; }
            var state = opened.track().getState();
            if (state == AudioTrackState.FINISHED || state == AudioTrackState.INACTIVE) {
                finish(sequence == 0 ? LazoDiscsText.audioNoFrames() : null, false);
            } else if (System.nanoTime() - lastFrame > TimeUnit.SECONDS.toNanos(30)) finish(LazoDiscsText.trackStuck(30000), false);
        } catch (Exception | LinkageError error) { fail(error); }
    }
    private void fail(Throwable error) {
        if (closed.get()) return;
        if (YoutubeLoginFailure.matches(error)) LazoDiscs.LOGGER.warn("Could not play '{}': {}", title, LazoDiscsText.youtubeLoginRequired());
        else LazoDiscs.LOGGER.warn("Could not play '" + title + "'", error);
        finish(messageOf(error), false);
    }
    private void finish(String failure, boolean manual) {
        if (!closed.compareAndSet(false, true)) return;
        ACTIVE.remove(this);
        synchronized (this) {
            // Forge reads mod classes through a shared interruptible archive channel.
            // Interrupting a first load can close that channel for the whole mod.
            // Closed checks discard late results; the resolver already has a timeout.
            if (manual && load != null) load.cancel(false);
            if (sender != null) sender.cancel(false);
            try { if (playback != null) playback.close(); }
            catch (RuntimeException | LinkageError error) { LazoDiscs.LOGGER.warn("Could not close audio decoder", error); }
            try {
                if (!manual && failure == null && sequence > 0) sink.finish();
                else sink.close();
            }
            catch (RuntimeException | LinkageError error) { LazoDiscs.LOGGER.warn("Could not remove audio source", error); }
        }
        if (!manual) finished.accept(failure);
    }
    @Override public void close() { finish(null, true); }
    public static void shutdown() {
        ScheduledExecutorService stopped;
        StreamingSession[] sessions;
        synchronized (StreamingSession.class) {
            stopped = clock;
            if (stopped == null) return;
            clock = null;
            sessions = ACTIVE.toArray(new StreamingSession[0]);
        }
        for (StreamingSession session : sessions) session.close();
        stopped.shutdown();
    }
    public static String messageOf(Throwable error) {
        if (YoutubeLoginFailure.matches(error)) return LazoDiscsText.youtubeLoginRequired();
        Set<Throwable> seen = ConcurrentHashMap.newKeySet();
        while (error.getCause() != null && seen.add(error) && (error.getMessage() == null || error.getMessage().equals(error.getCause().toString()))) error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
