package com.eyecrasher.lazodiscs.compat;

import com.eyecrasher.lazodiscs.LazoDiscs;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Optional Sable compatibility.
 *
 * <p>Sable stores assembled physics structures in plot/sub-level coordinates such as 20481032, 128,
 * 20481032. Those coordinates are not where the structure is rendered in the real world, so Plasmo
 * Voice sources must be projected out of the sub-level before being created.
 *
 * <p>This class uses reflection so LazoDiscs can still load without Sable installed.
 */
public final class SablePositionCompat {
    private static volatile boolean lookedUp;
    private static volatile Object helper;
    private static volatile Method projectOutOfSubLevel;

    private SablePositionCompat() {}

    public static Vec3 projectJukeboxCenter(Level level, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        return project(level, center);
    }

    public static Vec3 project(Level level, Vec3 position) {
        try {
            ensureLookup();
            Object h = helper;
            Method m = projectOutOfSubLevel;
            if (h == null || m == null) {
                return position;
            }
            Object result = m.invoke(h, level, position);
            if (result instanceof Vec3 projected) {
                return projected;
            }
        } catch (Throwable t) {
            if (lookedUp) {
                LazoDiscs.LOGGER.debug("Sable position projection failed: {}", t.toString());
            }
        }
        return position;
    }

    public static boolean isProbablySubLevel(BlockPos pos) {
        // Large vanilla coordinates are valid; only apply this heuristic with Sable present.
        return isSableLoaded()
                && (Math.abs(pos.getX()) > 1_000_000 || Math.abs(pos.getZ()) > 1_000_000);
    }

    public static boolean isSableLoaded() {
        try {
            ensureLookup();
        } catch (Throwable ignored) {
        }
        return helper != null && projectOutOfSubLevel != null;
    }

    private static void ensureLookup() throws ReflectiveOperationException {
        if (lookedUp) return;
        synchronized (SablePositionCompat.class) {
            if (lookedUp) return;
            try {
                Class<?> sable = Class.forName("dev.ryanhcode.sable.Sable");
                Field helperField = sable.getField("HELPER");
                Object h = helperField.get(null);
                Method m =
                        h.getClass()
                                .getMethod(
                                        "projectOutOfSubLevel",
                                        Level.class,
                                        net.minecraft.core.Position.class);
                helper = h;
                projectOutOfSubLevel = m;
                LazoDiscs.LOGGER.info(
                        "LazoDiscs detected Sable; sub-level sound positions will be projected to"
                                + " real world coordinates");
            } catch (ClassNotFoundException ignored) {
                helper = null;
                projectOutOfSubLevel = null;
            } finally {
                lookedUp = true;
            }
        }
    }
}
