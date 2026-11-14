package com.eyecrasher.lazodiscs;

import com.eyecrasher.lazodiscs.voice.LazoDiscsVoiceAddon;

import su.plo.voice.api.server.PlasmoVoiceServer;

public final class LazoDiscsServerBootstrap {
    private static volatile boolean loaded = false;

    private LazoDiscsServerBootstrap() {}

    public static synchronized void loadPlasmoAddon() {
        if (loaded) {
            return;
        }

        try {
            PlasmoVoiceServer.getAddonsLoader().load(new LazoDiscsVoiceAddon());
            loaded = true;
            LazoDiscs.LOGGER.info("LazoDiscs Plasmo Voice addon loaded");
        } catch (Throwable t) {
            // Dedicated servers usually load from mod construction.
            // Integrated singleplayer servers may need the later server-start lifecycle event.
            LazoDiscs.LOGGER.warn(
                    "Could not load LazoDiscs Plasmo Voice addon yet: {}", t.toString());
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static synchronized void resetForIntegratedServer() {
        loaded = false;
    }
}
