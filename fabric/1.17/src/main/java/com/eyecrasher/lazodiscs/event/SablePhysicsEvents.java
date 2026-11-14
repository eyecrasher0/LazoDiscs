package com.eyecrasher.lazodiscs.event;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.compat.SablePositionCompat;
import com.eyecrasher.lazodiscs.compat.sophisticatedbackpacks.LazoDiscsDiscHandler;
import com.eyecrasher.lazodiscs.server.JukeboxPlaybackManager;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.BiConsumer;

/**
 * Listens to Sable's post-physics-tick event to proactively update Plasmo Voice source positions
 * for LazoDiscs jukeboxes on moving platforms.
 *
 * <p>This class is <b>loader-agnostic</b> and uses <b>pure reflection</b> to access Sable's {@code
 * SableEventPlatform} cross-loader SPI. No compile-time dependency on Sable is needed — the class
 * probes for {@code dev.ryanhcode.sable.platform.SableEventPlatform} at runtime and gracefully
 * no-ops if Sable isn't installed.
 *
 * <h3>Why this exists</h3>
 *
 * <p>Previously, LazoDiscs updated dynamic source positions only on the regular server tick (every
 * 50 ms). For slow-moving platforms that's fine, but fast-moving airships would audibly lag. Sable
 * updates sub-level poses inside its own physics pipeline, which runs at a higher cadence
 * (typically multiple physics steps per server tick). By subscribing to Sable's post-physics-tick
 * event we can update source positions immediately after Sable finishes updating poses — minimizing
 * the lag to ~16 ms at 60 Hz.
 *
 * <h3>Fallback when Sable isn't installed</h3>
 *
 * <p>This class is loaded lazily via {@link #registerIfSablePresent()}, which uses {@link
 * SablePositionCompat#isSableLoaded()} to verify Sable is on the classpath before registering. If
 * Sable is not installed, no registration happens and the regular server-tick path remains the only
 * position-update mechanism.
 */
public final class SablePhysicsEvents {

    private SablePhysicsEvents() {}

    private static volatile boolean registered = false;

    // Reflection-cached SableEventPlatform.
    private static volatile boolean platformProbed = false;
    private static volatile Object platformInstance;
    private static volatile Method onPostPhysicsTickMethod;

    /**
     * Registers the Sable physics-tick listener via the cross-loader {@code SableEventPlatform}
     * SPI, but ONLY if Sable is actually installed. Idempotent.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void registerIfSablePresent() {
        if (registered) return;
        synchronized (SablePhysicsEvents.class) {
            if (registered) return;

            if (!SablePositionCompat.isSableLoaded()) {
                LazoDiscs.LOGGER.info(
                        "Sable not detected; skipping SablePostPhysicsTickEvent registration."
                                + " LazoDiscs will fall back to per-server-tick position updates"
                                + " only.");
                return;
            }

            try {
                ensurePlatformProbed();
                Object platform = platformInstance;
                Method onPostPhysicsTick = onPostPhysicsTickMethod;
                if (platform == null || onPostPhysicsTick == null) {
                    LazoDiscs.LOGGER.warn(
                            "Sable is loaded but SableEventPlatform.INSTANCE is null or "
                                    + "onPostPhysicsTick method not found. "
                                    + "Falling back to per-server-tick position updates only.");
                    return;
                }

                // Subscribe to post-physics-tick via reflection.
                // The BiConsumer receives (physicsSystem, timeStep) — we use Object types
                // since we don't have the Sable PhysicsSystem class on the compile classpath.
                BiConsumer<Object, Object> callback =
                        (physicsSystem, timeStep) -> {
                            try {
                                MinecraftServer server = LazoDiscs.getCurrentServer();
                                if (server == null) return;

                                for (ServerLevel level : server.getAllLevels()) {
                                    // Vanilla jukeboxes on Sable platforms
                                    // (JukeboxPlaybackManager).
                                    JukeboxPlaybackManager.INSTANCE.onSablePostPhysicsTick(level);
                                    // SophisticatedCore Jukebox Upgrade block-placed backpacks on
                                    // Sable.
                                    LazoDiscsDiscHandler.onSablePostPhysicsTick(level);
                                }
                            } catch (Throwable t) {
                                LazoDiscs.LOGGER.debug(
                                        "Error during SablePostPhysicsTickEvent handling: {}",
                                        t.toString());
                            }
                        };

                onPostPhysicsTick.invoke(platform, (Object) callback);

                registered = true;
                LazoDiscs.LOGGER.info(
                        "Sable detected; registered SablePostPhysicsTickEvent listener via"
                            + " SableEventPlatform for low-latency Plasmo Voice position updates on"
                            + " moving platforms.");
            } catch (Throwable t) {
                LazoDiscs.LOGGER.warn(
                        "Failed to register SablePostPhysicsTickEvent listener: {}", t.toString());
            }
        }
    }

    /**
     * Probes for the SableEventPlatform class and its INSTANCE field + onPostPhysicsTick method via
     * reflection. Idempotent.
     */
    private static void ensurePlatformProbed() {
        if (platformProbed) return;
        synchronized (SablePhysicsEvents.class) {
            if (platformProbed) return;
            try {
                Class<?> platformClass =
                        Class.forName("dev.ryanhcode.sable.platform.SableEventPlatform");
                Field instanceField = platformClass.getField("INSTANCE");
                Object instance = instanceField.get(null);
                if (instance != null) {
                    // Find onPostPhysicsTick(BiConsumer) method.
                    for (Method m : platformClass.getMethods()) {
                        if ("onPostPhysicsTick".equals(m.getName())
                                && m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == BiConsumer.class) {
                            platformInstance = instance;
                            onPostPhysicsTickMethod = m;
                            break;
                        }
                    }
                    if (onPostPhysicsTickMethod == null) {
                        LazoDiscs.LOGGER.warn(
                                "SableEventPlatform found but onPostPhysicsTick(BiConsumer) method"
                                        + " not found. Sable physics-tick integration disabled.");
                    }
                }
            } catch (Throwable t) {
                LazoDiscs.LOGGER.debug("SableEventPlatform probe failed: {}", t.toString());
            } finally {
                platformProbed = true;
            }
        }
    }
}
