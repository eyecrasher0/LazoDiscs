package com.eyecrasher.lazodiscs.paper;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.TypedKey;
import io.papermc.paper.registry.event.RegistryEvents;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

/** A silent vanilla song keeps jukebox/hopper/redstone state alive until our audio ends. */
public final class LazoDiscsBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager()
                .registerEventHandler(
                        RegistryEvents.JUKEBOX_SONG.compose(),
                        event -> {
                            // Preserve the comparator strength of each original disc.
                            for (int output = 0; output <= 15; output++) {
                                int strength = output;
                                event.registry()
                                        .register(
                                                TypedKey.create(
                                                        RegistryKey.JUKEBOX_SONG,
                                                        Key.key(
                                                                "lazodiscs",
                                                                "recording_" + output)),
                                                builder ->
                                                        builder.soundEvent(
                                                                        TypedKey.create(
                                                                                RegistryKey
                                                                                        .SOUND_EVENT,
                                                                                Key.key(
                                                                                        "minecraft:intentionally_empty")))
                                                                .description(Component.empty())
                                                                .lengthInSeconds(7 * 24 * 60 * 60)
                                                                .comparatorOutput(strength));
                            }
                        });
    }
}
