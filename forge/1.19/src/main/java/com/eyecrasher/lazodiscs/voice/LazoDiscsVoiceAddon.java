package com.eyecrasher.lazodiscs.voice;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.compat.sophisticatedbackpacks.LazoDiscsDiscHandler;

import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.InjectPlasmoVoice;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.server.PlasmoVoiceServer;

@Addon(
        id = "lazodiscs",
        name = "LazoDiscs",
        version = "1.0.8+1.19",
        authors = {"EyeCrasher"})
public final class LazoDiscsVoiceAddon implements AddonInitializer {
    private final YoutubeLoginLogFilter youtubeLoginLogs = new YoutubeLoginLogFilter(message -> LazoDiscs.LOGGER.warn("{}", message));
    @InjectPlasmoVoice private PlasmoVoiceServer voiceServer;

    @Override
    public void onAddonInitialize() {
        youtubeLoginLogs.install();
        PlasmoVoiceBridge.INSTANCE.initialize(voiceServer, this);
        LazoDiscs.LOGGER.info("LazoDiscs Plasmo Voice addon initialized");
    }

    @Override
    public void onAddonShutdown() {
        youtubeLoginLogs.close();
        PlasmoVoiceBridge.INSTANCE.shutdown();
        LazoDiscs.playback().stopAll("pv-addon-shutdown");
        LazoDiscsDiscHandler.stopAll("pv-addon-shutdown");
        LazoDiscs.LOGGER.info("LazoDiscs Plasmo Voice addon shut down");
    }
}
