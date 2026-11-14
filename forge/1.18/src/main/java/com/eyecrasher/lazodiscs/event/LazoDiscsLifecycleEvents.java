package com.eyecrasher.lazodiscs.event;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.LazoDiscsServerBootstrap;

import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class LazoDiscsLifecycleEvents {
    private LazoDiscsLifecycleEvents() {}

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        // This fires for the integrated singleplayer server too.
        LazoDiscs.setCurrentServer(event.getServer());
        LazoDiscsServerBootstrap.loadPlasmoAddon();
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        // Allows starting another singleplayer world in the same client session.
        LazoDiscsServerBootstrap.resetForIntegratedServer();
        LazoDiscs.setCurrentServer(null);
    }
}
