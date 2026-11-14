package com.eyecrasher.lazodiscs;

import com.eyecrasher.lazodiscs.command.LazoDiscsCommands;
import com.eyecrasher.lazodiscs.compat.sophisticatedbackpacks.LazoDiscsDiscHandler;
import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;
import com.eyecrasher.lazodiscs.event.JukeboxEvents;
import com.eyecrasher.lazodiscs.event.LazoDiscsLifecycleEvents;
import com.eyecrasher.lazodiscs.event.SablePhysicsEvents;
import com.eyecrasher.lazodiscs.server.JukeboxPlaybackManager;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;

import net.fabricmc.api.ModInitializer;
import net.minecraft.server.MinecraftServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LazoDiscs implements ModInitializer {
    public static final String MOD_ID = "lazodiscs";
    public static final Logger LOGGER = LoggerFactory.getLogger("LazoDiscs");

    private static volatile MinecraftServer currentServer;

    @Override
    public void onInitialize() {
        LazoDiscsConfig.load();
        LazoDiscsText.reload();

        LazoDiscsCommands.register();
        JukeboxEvents.register();
        LazoDiscsLifecycleEvents.register();

        // SophisticatedCore integration (no-op if not installed).
        LazoDiscsDiscHandler.register();

        // Sable physics-tick listener (no-op if Sable not installed).
        SablePhysicsEvents.registerIfSablePresent();

        // Dedicated servers load here; integrated singleplayer servers also retry from lifecycle
        // events.
        LazoDiscsServerBootstrap.loadPlasmoAddon();

        LOGGER.info("LazoDiscs initialized");
    }

    public static JukeboxPlaybackManager playback() {
        return JukeboxPlaybackManager.INSTANCE;
    }

    public static MinecraftServer getCurrentServer() {
        return currentServer;
    }

    public static void setCurrentServer(MinecraftServer server) {
        currentServer = server;
    }
}
