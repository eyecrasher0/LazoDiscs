package com.eyecrasher.lazodiscs;

import com.eyecrasher.lazodiscs.command.LazoDiscsCommands;
import com.eyecrasher.lazodiscs.compat.sophisticatedbackpacks.LazoDiscsDiscHandler;
import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;
import com.eyecrasher.lazodiscs.event.JukeboxEvents;
import com.eyecrasher.lazodiscs.event.LazoDiscsLifecycleEvents;
import com.eyecrasher.lazodiscs.event.SablePhysicsEvents;
import com.eyecrasher.lazodiscs.server.JukeboxPlaybackManager;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.slf4j.Logger;

@Mod(LazoDiscs.MOD_ID)
public final class LazoDiscs {
    public static final String MOD_ID = "lazodiscs";
    public static final Logger LOGGER = com.eyecrasher.lazodiscs.voice.AudioLogs.getLogger("LazoDiscs");

    private static volatile MinecraftServer currentServer;

    public static MinecraftServer getCurrentServer() {
        return currentServer;
    }

    public static void setCurrentServer(MinecraftServer server) {
        currentServer = server;
    }

    public LazoDiscs(IEventBus modBus) {
        ModLoadingContext.get()
                .getActiveContainer()
                .registerConfig(
                        ModConfig.Type.LOCAL, LazoDiscsConfig.SPEC, "lazodiscs/config.toml");
        LazoDiscsText.reload();

        NeoForge.EVENT_BUS.register(LazoDiscsCommands.class);
        NeoForge.EVENT_BUS.register(JukeboxEvents.class);
        NeoForge.EVENT_BUS.register(LazoDiscsLifecycleEvents.class);

        modBus.addListener(LazoDiscs::onCommonSetup);

        // LazoDiscs remains server-side for multiplayer.
        // Dedicated servers load here; integrated singleplayer servers also retry from the
        // lifecycle event.
        LazoDiscsServerBootstrap.loadPlasmoAddon();

        LOGGER.info("LazoDiscs initialized");
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(
                () -> {
                    LazoDiscsDiscHandler.register();
                    SablePhysicsEvents.registerIfSablePresent();
                });
    }

    public static JukeboxPlaybackManager playback() {
        return JukeboxPlaybackManager.INSTANCE;
    }
}
