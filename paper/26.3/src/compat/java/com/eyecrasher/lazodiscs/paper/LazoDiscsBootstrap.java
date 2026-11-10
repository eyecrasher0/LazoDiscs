package com.eyecrasher.lazodiscs.paper;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Older Paper exposes songs through datapack discovery, before the registry builder API. */
public final class LazoDiscsBootstrap implements PluginBootstrap {
    @Override
    public void bootstrap(BootstrapContext context) {
        context.getLifecycleManager()
                .registerEventHandler(
                        LifecycleEvents.DATAPACK_DISCOVERY,
                        event -> {
                            try {
                                Files.createDirectories(context.getDataDirectory());
                                var pack =
                                        context.getDataDirectory()
                                                .resolve("recordings-datapack.zip");
                                try (var source =
                                        getClass().getResourceAsStream("/lazodiscs-songs.zip")) {
                                    if (source == null)
                                        throw new IOException("Bundled song datapack is missing");
                                    Files.copy(source, pack, StandardCopyOption.REPLACE_EXISTING);
                                }
                                event.registrar()
                                        .discoverPack(
                                                pack,
                                                "lazodiscs-recordings",
                                                config -> config.autoEnableOnServerStart(true));
                            } catch (IOException failure) {
                                throw new IllegalStateException(
                                        "Could not register LazoDiscs songs", failure);
                            }
                        });
    }
}
