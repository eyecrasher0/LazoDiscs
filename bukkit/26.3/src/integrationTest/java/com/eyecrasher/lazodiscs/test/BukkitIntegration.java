package com.eyecrasher.lazodiscs.test;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.bukkit.Scheduling;
import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;
import com.eyecrasher.lazodiscs.data.CustomDiscData;
import com.eyecrasher.lazodiscs.data.DiscDataUtil;
import com.eyecrasher.lazodiscs.paper.JukeboxPlaybackManager;
import com.eyecrasher.lazodiscs.voice.LazoDiscsVoiceAddon;
import com.eyecrasher.lazodiscs.voice.Pv2Transport;
import com.eyecrasher.lazodiscs.voice.StreamingSession;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Executes public commands, item contracts and native world/source lifecycles in a real server. */
public final class BukkitIntegration extends JavaPlugin {
    private static final Map<String, Integer> VANILLA_SIGNALS = Map.ofEntries(
            Map.entry("music_disc_13", 1), Map.entry("music_disc_cat", 2),
            Map.entry("music_disc_blocks", 3), Map.entry("music_disc_chirp", 4),
            Map.entry("music_disc_far", 5), Map.entry("music_disc_mall", 6),
            Map.entry("music_disc_mellohi", 7), Map.entry("music_disc_stal", 8),
            Map.entry("music_disc_bounce", 8), Map.entry("music_disc_strad", 9),
            Map.entry("music_disc_lava_chicken", 9), Map.entry("music_disc_ward", 10),
            Map.entry("music_disc_tears", 10), Map.entry("music_disc_11", 11),
            Map.entry("music_disc_creator_music_box", 11), Map.entry("music_disc_wait", 12),
            Map.entry("music_disc_creator", 12), Map.entry("music_disc_pigstep", 13),
            Map.entry("music_disc_precipice", 13), Map.entry("music_disc_otherside", 14),
            Map.entry("music_disc_relic", 14), Map.entry("music_disc_5", 15));
    private Scheduling scheduler;
    private LazoDiscs plugin;
    private LazoDiscsVoiceAddon voice;
    private JukeboxPlaybackManager manager;
    private World world;
    private Location location;
    private String audio;
    private int passed;
    private final AtomicBoolean ended = new AtomicBoolean();
    private final UUID playerId = UUID.randomUUID();
    private ItemStack held;
    private Player player;
    private Object completedStream;

    @Override public void onEnable() {
        scheduler = new Scheduling(this);
        scheduler.global(() -> guarded(() -> {
            plugin = (LazoDiscs) Bukkit.getPluginManager().getPlugin("LazoDiscs");
            voice = (LazoDiscsVoiceAddon) field(plugin, "voice");
            manager = (JukeboxPlaybackManager) field(plugin, "playback");
            world = Bukkit.getWorlds().get(0);
            location = world.getSpawnLocation().clone();
            location.setY(Math.min(world.getMaxHeight() - 10, 100));
            audio = System.getProperty("lazodiscs.testAudioUrl");
            waitGlobal("Voice integration initialized", voice::isReady, 100, () -> {
                scheduler.region(location, () -> guarded(this::items));
            });
        }));
        // A hard wall-clock deadline catches region tasks accidentally scheduled onto unloaded regions.
        Bukkit.getServer().getLogger().info("LazoDiscs integration fixture started");
    }

    private void items() throws Exception {
        check(plugin.isEnabled(), "plugin enables on this server");
        check(Bukkit.getPluginManager().getPermission("lazodiscs.burn").getDefault() == PermissionDefault.OP, "burn permission defaults to op");
        check(Bukkit.getPluginManager().getPermission("lazodiscs.play").getDefault() == PermissionDefault.TRUE, "play permission defaults to everyone");
        int discs = 0;
        for (Material type : Material.values()) {
            if (!type.isItem() || !type.isRecord()) continue;
            ItemStack original = new ItemStack(type);
            var meta = original.getItemMeta();
            meta.setDisplayName("Owner name");
            meta.setLore(List.of("Owner lore"));
            meta.getPersistentDataContainer().set(new NamespacedKey("other", "test"), PersistentDataType.INTEGER, 42);
            original.setItemMeta(meta);
            var recorded = original.clone();
            var data = disc(audio);
            Integer comparator = VANILLA_SIGNALS.get(type.getKey().getKey());
            check(comparator != null, "vanilla comparator expectation for " + type);
            DiscDataUtil.write(recorded, data);
            check(DiscDataUtil.read(recorded).orElseThrow().equals(data), "record/read " + type);
            check(DiscDataUtil.comparatorOutput(recorded) == comparator, "comparator " + type);
            // The public Bukkit item serialization contract is the same one used by stored inventories.
            var encoded = new org.bukkit.configuration.file.YamlConfiguration();
            encoded.set("disc", recorded);
            var decoded = new org.bukkit.configuration.file.YamlConfiguration();
            decoded.loadFromString(encoded.saveToString());
            ItemStack restored = decoded.getItemStack("disc");
            check(DiscDataUtil.read(restored).orElseThrow().equals(data), "persistence " + type);
            DiscDataUtil.write(restored, disc(audio));
            DiscDataUtil.clear(restored);
            check(restored.equals(original), "erase preserves original name/lore/foreign data " + type);
            discs++;
        }
        persistedPolicy();
        check(discs >= 13, "all discs in this server version covered: " + discs);
        if (field(voice, "transport") instanceof Pv2Transport transport) {
            var entity = world.spawnEntity(location.clone().add(2,0,0), org.bukkit.entity.EntityType.ARMOR_STAND);
            try {
                var source = transport.entitySource(entity, "Moving fixture", 64);
                source.close();
                check(true, "Voice creates and releases native entity sources");
            } finally { entity.remove(); }
        }
        check(!DiscDataUtil.isMusicDisc(new ItemStack(Material.STONE)), "ordinary items rejected");
        try { DiscDataUtil.validateUrl("file:///etc/passwd"); throw new AssertionError("file URL accepted"); }
        catch (IllegalArgumentException expected) { check(true, "local file URLs rejected"); }
        Block block = location.getBlock();
        block.setType(Material.JUKEBOX);
        block.getChunk().addPluginChunkTicket(this);
        setupPlayer();
        held = new ItemStack(Material.MUSIC_DISC_CAT);
        DiscDataUtil.write(held, disc(audio));
        var event = interaction();
        manager.checkPermission(event); manager.insert(event);
        check(event.isCancelled() && held.getAmount() == 0, "insertion consumes exactly one disc");
        check(DiscDataUtil.read(((Jukebox) block.getState()).getRecord()).isPresent(), "recording inserted into native jukebox");
        waitRegion("positional stream begins", () -> activeCount() == 1, 100, () -> {
            Object active = ((Map<?,?>) field(manager, "active")).values().iterator().next();
            completedStream = field(field(active, "session"), "stream");
            waitRegion("track finishes and removes audio source", () -> activeCount() == 0 && streamCount() == 0, 220, this::cancelLoad);
        });
    }
    private void persistedPolicy() throws Exception {
        Object previous = plugin.getConfig().get("playback.maxRange");
        try {
            plugin.getConfig().set("playback.maxRange", 128);
            LazoDiscsConfig.load(plugin);
            String signed = audio + "?token=portable%2Bsignature";
            ItemStack portable = new ItemStack(Material.MUSIC_DISC_CAT);
            DiscDataUtil.write(portable, new CustomDiscData(signed, "Portable fixture", 128, 1, UUID.randomUUID()));
            var encoded = new org.bukkit.configuration.file.YamlConfiguration();
            encoded.set("disc", portable);
            String saved = encoded.saveToString();
            plugin.getConfig().set("playback.maxRange", 32);
            LazoDiscsConfig.load(plugin);
            var decoded = new org.bukkit.configuration.file.YamlConfiguration();
            decoded.loadFromString(saved);
            CustomDiscData read = DiscDataUtil.read(decoded.getItemStack("disc")).orElseThrow();
            check(read.range() == 32, "persisted recording obeys the current hearing cap");
            check(read.url().equals(signed), "portable signed URL retains its complete signature");
        } finally {
            plugin.getConfig().set("playback.maxRange", previous);
            LazoDiscsConfig.load(plugin);
        }
    }

    private void setupPlayer() {
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PlayerInventory.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getItemInMainHand" -> held;
            case "setItemInMainHand" -> { held = (ItemStack) args[0]; yield null; }
            default -> defaultValue(method.getReturnType());
        });
        player = (Player) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Player.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUniqueId" -> playerId;
            case "getInventory" -> inventory;
            case "hasPermission", "isOnline" -> true;
            case "getWorld" -> world;
            case "getLocation" -> location;
            case "getGameMode" -> GameMode.SURVIVAL;
            case "getName" -> "LazoDiscsFixture";
            case "hashCode" -> playerId.hashCode();
            case "equals" -> proxy == args[0];
            default -> defaultValue(method.getReturnType());
        });
    }
    private PlayerInteractEvent interaction() { return new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, held, location.getBlock(), BlockFace.UP, EquipmentSlot.HAND); }
    private void insert(String url) {
        Jukebox jukebox = (Jukebox) location.getBlock().getState();
        jukebox.eject();
        manager.validate();
        check(((Jukebox) location.getBlock().getState()).getRecord().getType().isAir(), "native ejection clears jukebox");
        held = new ItemStack(Material.MUSIC_DISC_CAT);
        DiscDataUtil.write(held, disc(url));
        var event = interaction(); manager.checkPermission(event); manager.insert(event);
    }
    private void cancelLoad() {
        check(((Number) field(completedStream, "sequence")).longValue() >= 145, "HTTP WAV decoded and 150 Opus frames passed to Voice transport");
        check(DiscDataUtil.read(((Jukebox) location.getBlock().getState()).getRecord()).isPresent(), "completion keeps the recorded item");
        ((Jukebox) location.getBlock().getState()).eject();manager.validate();
        waitRegion("ejection after EOF still retires the client tail", () -> ((AtomicBoolean) field(completedStream, "manualStop")).get(), 80, this::cancelPendingLoad);
    }
    private void cancelPendingLoad() {
        insert(audio.replace("test.wav", "held-cancel.wav"));
        waitRegion("cancel request is actually in flight", () -> control("cancel-started"), 80, () -> {
            Object stream = pendingStream();
            var load = (com.eyecrasher.lazodiscs.voice.AudioLoadTask) field(stream, "load");
            Jukebox jukebox = (Jukebox) location.getBlock().getState(); jukebox.eject();
            manager.validate();
            waitRegion("ejection retires source and loader while HTTP remains held",
                    () -> activeCount() == 0 && streamCount() == 0 && load.hasExited(), 100, () -> {
                check(!control("cancel-release") && !control("cancel-returned"), "cancellation precedes HTTP response");
                release("cancel");
                waitRegion("cancelled HTTP response released", () -> control("cancel-returned"), 100, () ->
                        unchangedAfterRelease(stream, 20, this::shutdownCheck));
            });
        });
    }
    private Path controls() { return Path.of(System.getProperty("lazodiscs.testHttpControl")); }
    private boolean control(String name) { return Files.exists(controls().resolve(name)); }
    private void release(String phase) throws Exception { Files.createFile(controls().resolve(phase + "-release")); }
    private Object pendingStream() {
        Object entry = ((Map<?,?>) field(manager, "active")).values().iterator().next();
        return field(field(entry, "session"), "stream");
    }
    private void unchangedAfterRelease(Object stream, int ticks, Checked next) {
        check(activeCount() == 0 && streamCount() == 0 && field(stream, "playback") == null
                && ((Number) field(stream, "sequence")).longValue() == 0, "released retired load cannot recreate playback");
        if (ticks == 0) guarded(next);
        else scheduler.region(location, () -> guarded(() -> unchangedAfterRelease(stream, ticks - 1, next)));
    }
    private void shutdownCheck() {
        insert(audio.replace("test.wav", "held-shutdown.wav"));
        waitRegion("shutdown request is actually in flight", () -> control("shutdown-started"), 80, () -> {
            Object stream = pendingStream();
            var load = (com.eyecrasher.lazodiscs.voice.AudioLoadTask) field(stream, "load");
            scheduler.global(() -> guarded(() -> {
                Bukkit.getPluginManager().disablePlugin(plugin);
                waitGlobal("disable retires source and loader while HTTP remains held", () -> activeCount() == 0 && streamCount() == 0 && load.hasExited(), 100, () -> {
                    check(!control("shutdown-release") && !control("shutdown-returned"), "disable precedes HTTP response");
                    check(((Map<?,?>) field(field(plugin, "commands"), "pending")).isEmpty(), "disable clears pending commands");
                    var commandMap = (org.bukkit.command.CommandMap) Bukkit.getServer().getClass().getMethod("getCommandMap").invoke(Bukkit.getServer());
                    check(commandMap.getCommand("lazodiscs:lazodisc") == null, "disable removes command aliases");
                    release("shutdown");
                    waitRegion("disabled HTTP response released", () -> control("shutdown-returned"), 100, () ->
                            unchangedAfterRelease(stream, 20, () -> finish(null)));
                });
            }));
        });
    }
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
    private CustomDiscData disc(String url) { return new CustomDiscData(url, "Fixture", 64, 1, UUID.randomUUID()); }
    private int activeCount() { return ((Map<?,?>) field(manager, "active")).size(); }
    private int streamCount() { return ((Set<?>) field(StreamingSession.class, "ACTIVE")).size(); }
    private static Object field(Object target, String name) {
        try { Class<?> type = target instanceof Class<?> ? (Class<?>) target : target.getClass(); Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target instanceof Class<?> ? null : target); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void waitGlobal(String label, BooleanSupplier ready, int ticks, Checked next) {
        waitGlobal(label, ready, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45), next);
    }
    private void waitGlobal(String label, BooleanSupplier ready, long deadline, Checked next) {
        if (ended.get()) return;
        if (ready.getAsBoolean()) { check(true, label); guarded(next); }
        else if (System.nanoTime() >= deadline) finish(new AssertionError("Timeout: " + label));
        else scheduler.global(() -> guarded(() -> waitGlobal(label, ready, deadline, next)));
    }
    private void waitRegion(String label, BooleanSupplier ready, int ticks, Checked next) {
        waitRegion(label, ready, System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45), next);
    }
    private void waitRegion(String label, BooleanSupplier ready, long deadline, Checked next) {
        if (ended.get()) return;
        if (ready.getAsBoolean()) { check(true, label); guarded(next); }
        else if (System.nanoTime() >= deadline) finish(new AssertionError("Timeout: " + label));
        else scheduler.region(location, () -> guarded(() -> waitRegion(label, ready, deadline, next)));
    }
    private void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); passed++; getLogger().info("PASS " + label); }
    private void guarded(Checked action) { if (!ended.get()) try { action.run(); } catch (Throwable error) { finish(error); } }
    private void finish(Throwable error) {
        if (!ended.compareAndSet(false,true)) return;
        if (error != null) getLogger().log(java.util.logging.Level.SEVERE,"Integration failed",error);
        try {
            String failure = error == null ? "null" : "\"" + error.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n"," ") + "\"";
            Files.writeString(Path.of(System.getProperty("lazodiscs.testResult")), "{\"passed\":"+passed+",\"failure\":"+failure+"}\n");
        } catch (Exception reportError) { getLogger().log(java.util.logging.Level.SEVERE,"Cannot write result",reportError); }
        scheduler.global(Bukkit::shutdown);
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
}
