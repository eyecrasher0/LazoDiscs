package com.eyecrasher.lazodiscs.bukkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Folia is optional; every entity/block operation is dispatched to its owning scheduler. */
public final class Scheduling {
    private final Plugin plugin;
    private final boolean folia;
    public Scheduling(Plugin plugin) {
        this.plugin = plugin;
        boolean detected;
        try { Class.forName("io.papermc.paper.threadedregions.RegionizedServer"); detected = true; }
        catch (ClassNotFoundException ordinaryServer) { detected = false; }
        folia = detected;
    }
    public boolean isFolia() { return folia; }
    public void global(Runnable action) {
        if (!folia) Bukkit.getScheduler().runTaskLater(plugin, action, 1L);
        else invoke(invoke(Bukkit.getServer(), "getGlobalRegionScheduler"), "run", plugin, (Consumer<Object>) task -> action.run());
    }
    public void repeatGlobal(Runnable action, long ticks) {
        if (!folia) Bukkit.getScheduler().runTaskTimer(plugin, action, ticks, ticks);
        else invoke(invoke(Bukkit.getServer(), "getGlobalRegionScheduler"), "runAtFixedRate", plugin, (Consumer<Object>) task -> action.run(), ticks, ticks);
    }
    public void region(Location location, Runnable action) {
        if (!folia) Bukkit.getScheduler().runTaskLater(plugin, action, 1L);
        else invoke(invoke(Bukkit.getServer(), "getRegionScheduler"), "run", plugin, location, (Consumer<Object>) task -> action.run());
    }
    public void player(Player player, Runnable action, Runnable retired) {
        if (!folia) Bukkit.getScheduler().runTaskLater(plugin, action, 1L);
        else if (invoke(invoke(player, "getScheduler"), "run", plugin, (Consumer<Object>) task -> action.run(), retired) == null) retired.run();
    }
    public void cancel() {
        if (!folia) Bukkit.getScheduler().cancelTasks(plugin);
        else invoke(invoke(Bukkit.getServer(), "getGlobalRegionScheduler"), "cancelTasks", plugin);
    }
    private static Object invoke(Object target, String name, Object... arguments) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) continue;
            try { return method.invoke(target, arguments); }
            catch (IllegalArgumentException mismatch) { continue; }
            catch (ReflectiveOperationException error) {
                Throwable cause = error instanceof InvocationTargetException ? error.getCause() : error;
                throw new IllegalStateException("Could not schedule " + name, cause);
            }
        }
        throw new IllegalStateException("Missing scheduler method: " + name);
    }
}
