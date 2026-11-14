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
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(LazoDiscs.MOD_ID)
public final class LazoDiscs {
    public static final String MOD_ID = "lazodiscs";
    public static final Logger LOGGER = LoggerFactory.getLogger("LazoDiscs");

    private static volatile MinecraftServer currentServer;

    public static MinecraftServer getCurrentServer() {
        return currentServer;
    }

    public static void setCurrentServer(MinecraftServer server) {
        currentServer = server;
    }

    public LazoDiscs() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        net.minecraftforge.fml.ModLoadingContext.get()
                .registerConfig(
                        ModConfig.Type.COMMON, LazoDiscsConfig.SPEC, "lazodiscs/config.toml");
        LazoDiscsText.reload();

        MinecraftForge.EVENT_BUS.register(LazoDiscsCommands.class);
        MinecraftForge.EVENT_BUS.register(JukeboxEvents.class);
        MinecraftForge.EVENT_BUS.register(LazoDiscsLifecycleEvents.class);

        modBus.addListener(LazoDiscs::onCommonSetup);

        // LazoDiscs remains server-side for multiplayer.
        // Dedicated servers load here; integrated singleplayer servers load from the lifecycle
        // event.

        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            LazoDiscsServerBootstrap.loadPlasmoAddon();
        }

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
