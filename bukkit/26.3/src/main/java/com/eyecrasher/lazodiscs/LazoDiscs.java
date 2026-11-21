package com.eyecrasher.lazodiscs;

import com.eyecrasher.lazodiscs.bukkit.Scheduling;
import com.eyecrasher.lazodiscs.command.LazoDiscsCommands;
import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;
import com.eyecrasher.lazodiscs.paper.JukeboxPlaybackManager;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import com.eyecrasher.lazodiscs.voice.AudioLoadExecutor;
import com.eyecrasher.lazodiscs.voice.LavaPcmFeeder;
import com.eyecrasher.lazodiscs.voice.LazoDiscsVoiceAddon;
import com.eyecrasher.lazodiscs.voice.StreamingSession;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.slf4j.Logger;
import java.util.List;

/** Bukkit compatibility distribution for Spigot, Purpur and region-threaded Folia. */
public final class LazoDiscs extends JavaPlugin {
    public static final Logger LOGGER = com.eyecrasher.lazodiscs.voice.AudioLogs.getLogger("LazoDiscs");
    private final LazoDiscsVoiceAddon voice = new LazoDiscsVoiceAddon(this);
    private JukeboxPlaybackManager playback;
    private LazoDiscsCommands commands;
    private Scheduling scheduler;
    private volatile boolean stopping;
    private Command rootCommand;
    private CommandMap commandMap;

    @Override public void onLoad() {
        LazoDiscsConfig.load(this);
        LazoDiscsText.reload();
        voice.load();
    }
    @Override public void onEnable() {
        scheduler = new Scheduling(this);
        voice.enable();
        playback = new JukeboxPlaybackManager(this, voice);
        commands = new LazoDiscsCommands(this);
        getServer().getPluginManager().registerEvents(playback, this);
        getServer().getPluginManager().registerEvents(commands, this);
        String alias = LazoDiscsConfig.COMMAND_ALIAS.get();
        rootCommand = new Command(alias, "Record music discs", "/" + alias,
                alias.equals("lazodisc") ? List.of() : List.of("lazodisc")) {
            @Override public boolean execute(CommandSender sender, String label, String[] args) {
                if (stopping) return false;
                commands.execute(sender, args); return true;
            }
            @Override public List<String> tabComplete(CommandSender sender, String label, String[] args) {
                return stopping ? List.of() : List.copyOf(commands.suggest(sender, args));
            }
        };
        try {
            // CraftBukkit has exposed this hook throughout the supported 1.16+ range.
            commandMap = (CommandMap) getServer().getClass().getMethod("getCommandMap").invoke(getServer());
            commandMap.register("lazodiscs", rootCommand);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot register LazoDiscs command", error); }
        scheduler.global(playback::resumeLoadedChunks);
        scheduler.repeatGlobal(playback::validate, LazoDiscsConfig.VALIDATION_INTERVAL_TICKS.get());
        LOGGER.info("LazoDiscs enabled with {} scheduling", scheduler.isFolia() ? "Folia region" : "Bukkit");
    }
    @Override public void onDisable() {
        stopping = true;
        if (scheduler != null) scheduler.cancel();
        if (rootCommand != null && commandMap != null) {
            try {
                Object table;
                try { table = commandMap.getClass().getMethod("getKnownCommands").invoke(commandMap); }
                catch (NoSuchMethodException olderSpigot) {
                    var field = org.bukkit.command.SimpleCommandMap.class.getDeclaredField("knownCommands");
                    field.setAccessible(true); table = field.get(commandMap);
                }
                @SuppressWarnings("unchecked")
                var known = (java.util.Map<String, Command>) table;
                var aliases = known.entrySet().stream().filter(entry -> entry.getValue() == rootCommand)
                        .map(java.util.Map.Entry::getKey).toList();
                aliases.forEach(known::remove);
            } catch (ReflectiveOperationException | RuntimeException error) { LOGGER.warn("Cannot remove command aliases", error); }
            rootCommand.unregister(commandMap);
        }
        if (commands != null) commands.shutdown();
        if (playback != null) playback.close();
        AudioLoadExecutor.shutdownNow();
        if (voice.hasLoadedAudio()) LavaPcmFeeder.shutdown();
        voice.unload();
    }
    public void onMain(Runnable action) { if (!stopping && isEnabled()) scheduler.global(() -> { if (!stopping) action.run(); }); }
    public void onRegion(Location location, Runnable action) { if (!stopping && isEnabled()) scheduler.region(location, () -> { if (!stopping) action.run(); }); }
    public void onLegacyRegion(Location location, Runnable action) {
        if (isEnabled() && !scheduler.isFolia() && Bukkit.isPrimaryThread()) action.run();
        else onRegion(location, action);
    }
    public void prepareLegacyDisable() {
        if (scheduler == null || scheduler.isFolia() || !Bukkit.isPrimaryThread()) return;
        stopping = true;
        if (playback != null) playback.stopAll();
        StreamingSession.shutdown();
    }
    public void onPlayer(Player player, Runnable action, Runnable retired) { if (!stopping && isEnabled()) scheduler.player(player, () -> { if (!stopping) action.run(); }, retired); }
    public void onPlayer(Player player, Runnable action) {
        Runnable retired = commands == null ? () -> {} : commands.retirement(player);
        onPlayer(player, action, retired);
    }
    public void markAudioUsed() { voice.markAudioUsed(); }
    public void voiceStopped() { onMain(() -> { if (playback != null) playback.stopAll(); }); }
}
