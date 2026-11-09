package com.eyecrasher.lazodiscs.voice;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;
import com.eyecrasher.lazodiscs.data.CustomDiscData;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;

import org.bukkit.Location;

import su.plo.slib.api.server.position.ServerPos3d;
import su.plo.slib.api.server.world.McServerWorld;
import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.InjectPlasmoVoice;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.line.ServerSourceLine;
import su.plo.voice.api.server.audio.provider.AudioFrameProvider;
import su.plo.voice.api.server.audio.provider.AudioFrameResult;
import su.plo.voice.api.server.audio.source.AudioSender;
import su.plo.voice.api.server.audio.source.ServerStaticSource;

import java.io.InputStream;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Addon(
        id = "lazodiscs",
        name = "LazoDiscs",
        version = "1.0.7+26.3",
        authors = {"EyeCrasher"})
public final class LazoDiscsVoiceAddon implements AddonInitializer {
    private final LazoDiscs plugin;
    @InjectPlasmoVoice private PlasmoVoiceServer server;
    private volatile ServerSourceLine line;
    private volatile boolean loadedAudio;

    public LazoDiscsVoiceAddon(LazoDiscs plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onAddonInitialize() {
        try (InputStream icon =
                getClass()
                        .getClassLoader()
                        .getResourceAsStream("assets/lazodiscs/textures/icons/discs.png")) {
            if (icon == null) throw new IllegalStateException("Discs source icon is missing");
            var builder =
                    server.getSourceLineManager()
                            .createBuilder(this, "discs", LazoDiscsText.sourceLineName(), icon, 50);
            builder.setDefaultVolume(LazoDiscsConfig.SOURCE_LINE_DEFAULT_VOLUME.get().floatValue());
            line = builder.build();
            LazoDiscs.LOGGER.info("Plasmo Voice Discs source line ready");
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize Plasmo Voice integration", e);
        }
    }

    @Override
    public void onAddonShutdown() {
        line = null;
        plugin.voiceStopped();
    }

    public boolean isReady() {
        return line != null;
    }

    public boolean hasLoadedAudio() {
        return loadedAudio;
    }

    public void markAudioUsed() {
        loadedAudio = true;
    }

    /**
     * Called on the server thread; uses the actual Bukkit world instance, including custom worlds.
     */
    public Session create(Location location, CustomDiscData disc, Consumer<String> finished) {
        ServerSourceLine sourceLine = line;
        if (sourceLine == null) throw new IllegalStateException("Plasmo Voice is not initialized");
        McServerWorld world = server.getMinecraftServer().getWorld((Object) location.getWorld());
        if (world == null)
            throw new IllegalStateException(
                    "Plasmo Voice world is unavailable: " + location.getWorld().getName());
        ServerStaticSource source =
                sourceLine.createStaticSource(
                        new ServerPos3d(
                                world,
                                location.getX() + 0.5,
                                location.getY() + 0.5,
                                location.getZ() + 0.5),
                        false);
        source.setName(disc.title());
        return new Session(source, disc, finished);
    }

    public final class Session {
        private final Object lock = new Object();
        private final ServerStaticSource source;
        private final CustomDiscData disc;
        private final Consumer<String> finished;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<String> failure = new AtomicReference<>();
        private volatile Future<?> task;
        private LavaPcmFeeder.StreamingPlayback playback;
        private AudioSender sender;

        private Session(ServerStaticSource source, CustomDiscData disc, Consumer<String> finished) {
            this.source = source;
            this.disc = disc;
            this.finished = finished;
        }

        public void start() {
            markAudioUsed();
            task =
                    AudioLoadExecutor.submit(
                            () -> {
                                try {
                                    if (closed.get()) return;
                                    var opened =
                                            LavaPcmFeeder.openStream(
                                                    disc.url(), disc.title(), disc.volume());
                                    synchronized (lock) {
                                        if (closed.get()) {
                                            opened.close();
                                            return;
                                        }
                                        playback = opened;
                                        playback.player()
                                                .addListener(
                                                        new AudioEventAdapter() {
                                                            @Override
                                                            public void onTrackException(
                                                                    AudioPlayer player,
                                                                    AudioTrack track,
                                                                    FriendlyException error) {
                                                                failure.compareAndSet(
                                                                        null, messageOf(error));
                                                            }

                                                            @Override
                                                            public void onTrackStuck(
                                                                    AudioPlayer player,
                                                                    AudioTrack track,
                                                                    long thresholdMs) {
                                                                failure.compareAndSet(
                                                                        null,
                                                                        LazoDiscsText.trackStuck(
                                                                                thresholdMs));
                                                            }
                                                        });
                                        sender =
                                                source.createAudioSender(
                                                        provider(opened), (short) disc.range());
                                        sender.onStop(() -> finish(false));
                                        sender.start();
                                    }
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    if (!closed.get()) {
                                        failure.set("Audio loading interrupted");
                                        finish(false);
                                    }
                                } catch (Exception | LinkageError error) {
                                    if (!closed.get()) {
                                        failure.compareAndSet(null, messageOf(error));
                                        LazoDiscs.LOGGER.warn(
                                                "Could not play '{}': {}",
                                                disc.title(),
                                                error.toString());
                                        finish(false);
                                    }
                                }
                            },
                            () -> {
                                failure.set(LazoDiscsText.audioLoadBusy());
                                finish(false);
                            });
            if (closed.get()) task.cancel(true);
        }

        private AudioFrameProvider provider(LavaPcmFeeder.StreamingPlayback opened) {
            return new AudioFrameProvider() {
                private long lastFrame = System.nanoTime();
                private boolean providedFrame;

                @Override
                public AudioFrameResult provide20ms() {
                    if (closed.get() || failure.get() != null)
                        return AudioFrameResult.Finished.INSTANCE;
                    try {
                        // Always drain buffered frames before checking the decoder's finished
                        // state.
                        AudioFrame frame = opened.player().provide();
                        if (frame != null) {
                            lastFrame = System.nanoTime();
                            providedFrame = true;
                            return new AudioFrameResult.Provided(
                                    server.getDefaultEncryption().encrypt(frame.getData()));
                        }
                        AudioTrackState state = opened.track().getState();
                        if (state == AudioTrackState.FINISHED
                                || (state == AudioTrackState.INACTIVE
                                        && opened.track().getPosition() > 0)) {
                            if (!providedFrame)
                                failure.compareAndSet(null, LazoDiscsText.audioNoFrames());
                            return AudioFrameResult.Finished.INSTANCE;
                        }
                        if (System.nanoTime() - lastFrame > 30_000_000_000L) {
                            failure.compareAndSet(null, LazoDiscsText.trackStuck(30_000));
                            return AudioFrameResult.Finished.INSTANCE;
                        }
                        return new AudioFrameResult.Provided(null);
                    } catch (Exception | LinkageError error) {
                        failure.compareAndSet(null, messageOf(error));
                        return AudioFrameResult.Finished.INSTANCE;
                    }
                }
            };
        }

        public void stop() {
            finish(true);
        }

        private void finish(boolean manual) {
            if (!closed.compareAndSet(false, true)) return;
            AudioSender oldSender;
            LavaPcmFeeder.StreamingPlayback oldPlayback;
            synchronized (lock) {
                oldSender = sender;
                oldPlayback = playback;
                sender = null;
                playback = null;
            }
            Future<?> oldTask = task;
            if (manual && oldTask != null) oldTask.cancel(true);
            try {
                if (oldSender != null) oldSender.stop();
            } catch (RuntimeException error) {
                LazoDiscs.LOGGER.warn("Could not stop disc sender: {}", error.toString());
            }
            try {
                if (oldPlayback != null) oldPlayback.close();
            } finally {
                try {
                    source.remove();
                } catch (RuntimeException error) {
                    LazoDiscs.LOGGER.warn("Could not remove disc source: {}", error.toString());
                }
            }
            if (!manual) plugin.onMain(() -> finished.accept(failure.get()));
        }
    }

    public static String messageOf(Throwable error) {
        while (error.getCause() != null
                && (error.getMessage() == null
                        || error.getMessage().equals(error.getCause().toString())))
            error = error.getCause();
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
