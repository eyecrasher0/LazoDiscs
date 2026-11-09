package com.eyecrasher.lazodiscs;

import com.eyecrasher.lazodiscs.command.LazoDiscsCommands;
import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;
import com.eyecrasher.lazodiscs.paper.JukeboxPlaybackManager;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import com.eyecrasher.lazodiscs.voice.AudioLoadExecutor;
import com.eyecrasher.lazodiscs.voice.LavaPcmFeeder;
import com.eyecrasher.lazodiscs.voice.LazoDiscsVoiceAddon;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import su.plo.voice.api.server.PlasmoVoiceServer;

import java.util.List;

public final class LazoDiscs extends JavaPlugin {
    public static final Logger LOGGER = LoggerFactory.getLogger("LazoDiscs");
    private final LazoDiscsVoiceAddon voice = new LazoDiscsVoiceAddon(this);
    private JukeboxPlaybackManager playback;
    private LazoDiscsCommands commands;
    private volatile boolean stopping;

    @Override
    public void onLoad() {
        LazoDiscsConfig.load(this);
        LazoDiscsText.reload();
        PlasmoVoiceServer.getAddonsLoader().load(voice);
    }

    @Override
    public void onEnable() {
        playback = new JukeboxPlaybackManager(this, voice);
        commands = new LazoDiscsCommands(this);
        getServer().getPluginManager().registerEvents(playback, this);
        getServer().getPluginManager().registerEvents(commands, this);
        getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.COMMANDS,
                        event -> {
                            String alias = LazoDiscsConfig.COMMAND_ALIAS.get();
                            event.registrar()
                                    .register(
                                            alias,
                                            "Record music discs",
                                            alias.equals("lazodisc")
                                                    ? List.of()
                                                    : List.of("lazodisc"),
                                            commands);
                        });
        getServer().getScheduler().runTask(this, playback::resumeLoadedChunks);
        int interval = LazoDiscsConfig.VALIDATION_INTERVAL_TICKS.get();
        getServer().getScheduler().runTaskTimer(this, playback::validate, interval, interval);
        LOGGER.info("LazoDiscs Paper enabled; command /{}", LazoDiscsConfig.COMMAND_ALIAS.get());
    }

    @Override
    public void onDisable() {
        stopping = true;
        if (commands != null) commands.shutdown();
        if (playback != null) playback.stopAll();
        AudioLoadExecutor.shutdownNow();
        // Do not initialize the audio engine just to stop an unused plugin.
        if (voice.hasLoadedAudio()) LavaPcmFeeder.shutdown();
        PlasmoVoiceServer.getAddonsLoader().unload(voice);
    }

    /** Network/audio callbacks never inspect or mutate Bukkit state off the server thread. */
    public void onMain(Runnable action) {
        if (stopping || !isEnabled()) return;
        try {
            getServer()
                    .getScheduler()
                    .runTask(
                            this,
                            () -> {
                                if (!stopping) action.run();
                            });
        } catch (IllegalPluginAccessException ignored) {
            // Plugin was disabled between the enabled check and scheduling.
        }
    }

    public void markAudioUsed() {
        voice.markAudioUsed();
    }

    public void voiceStopped() {
        onMain(
                () -> {
                    if (playback != null) playback.stopAll();
                });
    }
}
