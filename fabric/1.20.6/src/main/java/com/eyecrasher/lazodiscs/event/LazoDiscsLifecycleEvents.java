package com.eyecrasher.lazodiscs.event;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.LazoDiscsServerBootstrap;
import com.eyecrasher.lazodiscs.voice.AudioLoadExecutor;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class LazoDiscsLifecycleEvents {
    private LazoDiscsLifecycleEvents() {}

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(
                server -> {
                    LazoDiscs.setCurrentServer(server);
                    LazoDiscsServerBootstrap.loadPlasmoAddon();
                });
        ServerLifecycleEvents.SERVER_STOPPED.register(
                server -> {
                    LazoDiscs.setCurrentServer(null);
                    AudioLoadExecutor.shutdownNow();
                    // Allows starting another singleplayer world in the same client session.
                    LazoDiscsServerBootstrap.resetForIntegratedServer();
                });
    }
}
