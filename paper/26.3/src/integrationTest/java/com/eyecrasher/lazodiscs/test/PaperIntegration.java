package com.eyecrasher.lazodiscs.test;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;
import com.eyecrasher.lazodiscs.data.CustomDiscData;
import com.eyecrasher.lazodiscs.data.DiscDataUtil;
import com.eyecrasher.lazodiscs.voice.LavaPcmFeeder;
import com.eyecrasher.lazodiscs.voice.LazoDiscsVoiceAddon;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;

import net.kyori.adventure.text.Component;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Hopper;
import org.bukkit.block.Jukebox;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import su.plo.voice.api.server.audio.line.ServerSourceLine;
import su.plo.voice.api.server.audio.source.ServerStaticSource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Runs inside a disposable real Paper server. Never shipped with LazoDiscs. */
public final class PaperIntegration extends JavaPlugin {
    private final List<String> passed = new ArrayList<>();
    private LazoDiscs plugin;
    private LazoDiscsVoiceAddon voice;
    private World world;
    private World otherWorld;
    private String audio;
    private boolean finished;

    @Override
    public void onEnable() {
        later(20, this::start);
    }

    private void start() throws Exception {
        plugin = (LazoDiscs) Bukkit.getPluginManager().getPlugin("LazoDiscs");
        voice = (LazoDiscsVoiceAddon) field(plugin, "voice");
        world = Bukkit.getWorlds().getFirst();
        audio = System.getProperty("lazodiscs.testAudioUrl");
        check(audio != null && voice.isReady(), "Plasmo Voice source line initialized");
        check(
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lazodisc burn " + audio),
                "command registered with Paper");
        check(
                Bukkit.getPluginManager().getPermission("lazodiscs.burn").getDefault()
                        == PermissionDefault.OP,
                "burn defaults to operators");
        check(
                Bukkit.getPluginManager().getPermission("lazodiscs.play").getDefault()
                        == PermissionDefault.TRUE,
                "play defaults to all players");
        itemRoundTrips();
        urlPolicy();
        var other =
                new WorldCreator("lazo_other")
                        .type(WorldType.FLAT)
                        .generateStructures(false)
                        .createWorld();
        otherWorld = other;
        check(other != null, "custom Bukkit world created");
        var source = voice.create(other.getSpawnLocation(), disc(audio), failure -> {});
        var pvSource = (ServerStaticSource) field(source, "source");
        check(
                pvSource.getPosition().getWorld().getName().equals(other.getName()),
                "source resolves the exact Bukkit world");
        source.stop();
        check(sourceCount() == 0, "unused source removed");
        world.setChunkForceLoaded(12, 0, true);
        later(2, () -> compareRedstone(0));
    }

    private void compareRedstone(int index) {
        var types =
                java.util.Arrays.stream(Material.values())
                        .filter(
                                t ->
                                        t.name().startsWith("MUSIC_DISC_")
                                                && t.isItem()
                                                && DiscDataUtil.isMusicDisc(new ItemStack(t)))
                        .toList();
        if (index == types.size()) {
            decodeAudio();
            return;
        }
        Material type = types.get(index);
        ItemStack plain = new ItemStack(type);
        ItemStack burned = plain.clone();
        DiscDataUtil.write(burned, disc(audio));
        comparatorFixture(0, plain);
        comparatorFixture(4, burned);
        later(
                6,
                () -> {
                    int original =
                            ((org.bukkit.block.data.type.RedstoneWire)
                                            world.getBlockAt(202, 80, 0).getBlockData())
                                    .getPower();
                    int custom =
                            ((org.bukkit.block.data.type.RedstoneWire)
                                            world.getBlockAt(202, 80, 4).getBlockData())
                                    .getPower();
                    check(
                            original > 0 && original == custom,
                            "native comparator redstone retained for "
                                    + type
                                    + " ("
                                    + original
                                    + ")");
                    compareRedstone(index + 1);
                });
    }

    private void comparatorFixture(int z, ItemStack item) {
        for (int x = 200; x <= 202; x++) world.getBlockAt(x, 79, z).setType(Material.STONE);
        world.getBlockAt(200, 80, z).setType(Material.JUKEBOX);
        world.getBlockAt(201, 80, z).setType(Material.COMPARATOR);
        var comparator =
                (org.bukkit.block.data.type.Comparator) world.getBlockAt(201, 80, z).getBlockData();
        comparator.setFacing(org.bukkit.block.BlockFace.WEST);
        world.getBlockAt(201, 80, z).setBlockData(comparator);
        world.getBlockAt(202, 80, z).setType(Material.REDSTONE_WIRE);
        var jukebox = (Jukebox) world.getBlockAt(200, 80, z).getState();
        jukebox.setRecord(item);
        jukebox.update();
    }

    private void itemRoundTrips() {
        int count = 0;
        for (Material type : Material.values()) {
            if (!type.name().startsWith("MUSIC_DISC_") || !type.isItem()) continue;
            ItemStack original = new ItemStack(type);
            if (!DiscDataUtil.isMusicDisc(original)) continue;
            original.editMeta(
                    meta -> {
                        meta.displayName(Component.text("Owner's name"));
                        meta.lore(List.of(Component.text("Owner's lore")));
                        meta.getPersistentDataContainer()
                                .set(
                                        new NamespacedKey("other_plugin", "value"),
                                        PersistentDataType.INTEGER,
                                        42);
                    });
            ItemStack burned = original.clone();
            var expected = disc(audio);
            int comparator = DiscDataUtil.comparatorOutput(original);
            DiscDataUtil.write(burned, expected);
            check(DiscDataUtil.read(burned).orElseThrow().equals(expected), "record/read " + type);
            check(DiscDataUtil.comparatorOutput(burned) == comparator, "comparator " + type);
            ItemStack restored = ItemStack.deserializeBytes(burned.serializeAsBytes());
            check(
                    DiscDataUtil.read(restored).orElseThrow().equals(expected),
                    "world serialization " + type);
            DiscDataUtil.write(
                    restored, new CustomDiscData(audio, "Second title", 64, 1, UUID.randomUUID()));
            DiscDataUtil.clear(restored);
            check(restored.equals(original), "erase restores name, lore, data and song " + type);
            count++;
        }
        check(count >= 19, "all vanilla music discs covered (" + count + ")");
        check(!DiscDataUtil.isMusicDisc(new ItemStack(Material.STONE)), "ordinary items rejected");
        ItemStack malformed = new ItemStack(Material.MUSIC_DISC_CAT);
        DiscDataUtil.write(malformed, disc(audio));
        malformed.editMeta(
                meta -> {
                    var root = new NamespacedKey("lazodiscs", "disc");
                    var data =
                            meta.getPersistentDataContainer()
                                    .get(root, PersistentDataType.TAG_CONTAINER);
                    data.set(
                            new NamespacedKey("lazodiscs", "volume"),
                            PersistentDataType.FLOAT,
                            Float.NaN);
                    meta.getPersistentDataContainer()
                            .set(root, PersistentDataType.TAG_CONTAINER, data);
                });
        check(DiscDataUtil.read(malformed).isEmpty(), "malformed non-finite volume ignored");
    }

    private void urlPolicy() {
        check(
                DiscDataUtil.validateUrl("spotify:track:4uLU6hMCjMI75M1A2tKUQC")
                        .startsWith("https://open.spotify.com/track/"),
                "Spotify URI canonicalized");
        reject("file:///etc/passwd", "local file scheme rejected");
        reject("https://open.spotify.com/album/4uLU6hMCjMI75M1A2tKUQC", "Spotify albums rejected");
        reject("https://user:password@example.com/a.mp3", "URL credentials rejected");
        plugin.getConfig().set("security.allowedDomains", List.of("youtube.com"));
        LazoDiscsConfig.load(plugin);
        check(
                DiscDataUtil.validateUrl("https://music.youtube.com/watch?v=fixture")
                        .contains("youtube.com"),
                "allowlist permits subdomains");
        reject("https://youtube.com.evil.example/a.mp3", "allowlist rejects misleading hostname");
        plugin.getConfig().set("security.allowedDomains", List.of());
        plugin.getConfig().set("security.allowHttp", false);
        LazoDiscsConfig.load(plugin);
        reject(audio, "HTTP disabled by policy");
        plugin.getConfig().set("security.allowHttp", true);
        LazoDiscsConfig.load(plugin);
    }

    private void decodeAudio() {
        plugin.markAudioUsed();
        Thread.startVirtualThread(
                () -> {
                    try (var stream = LavaPcmFeeder.openStream(audio, "Fixture", 1)) {
                        int frames = 0;
                        long deadline = System.nanoTime() + 15_000_000_000L;
                        while (System.nanoTime() < deadline) {
                            var frame = stream.player().provide();
                            if (frame != null) {
                                if (frame.getData().length == 0)
                                    throw new AssertionError("empty Opus frame");
                                frames++;
                            } else if (stream.track().getState() == AudioTrackState.FINISHED
                                    || (stream.track().getState() == AudioTrackState.INACTIVE
                                            && frames > 0)) break;
                            else Thread.sleep(5);
                        }
                        int decoded = frames;
                        plugin.onMain(
                                () ->
                                        guarded(
                                                () -> {
                                                    check(
                                                            decoded >= 145,
                                                            "real HTTP WAV decoded and encoded to"
                                                                    + " Opus ("
                                                                    + decoded
                                                                    + " frames)");
                                                    hopperPlayback();
                                                }));
                    } catch (Throwable error) {
                        plugin.onMain(() -> finish(error));
                    }
                });
    }

    private void hopperPlayback() {
        world.setChunkForceLoaded(0, 0, true);
        world.getBlockAt(8, 80, 8).setType(Material.JUKEBOX);
        world.getBlockAt(8, 81, 8).setType(Material.HOPPER);
        world.getBlockAt(8, 79, 8).setType(Material.HOPPER);
        ItemStack item = new ItemStack(Material.MUSIC_DISC_CAT);
        var expected = disc(audio);
        DiscDataUtil.write(item, expected);
        ((Hopper) world.getBlockAt(8, 81, 8).getState()).getInventory().setItem(0, item);
        waitFor(
                "real hopper insertion starts positional source",
                () -> activeCount() == 1,
                160,
                () -> {
                    check(sourceCount() == 1, "one Plasmo source for one jukebox");
                    check(
                            ((Jukebox) world.getBlockAt(8, 80, 8).getState()).isPlaying(),
                            "jukebox keeps native playing state");
                    waitFor(
                            "audio ends and bottom hopper extracts the disc",
                            () -> {
                                var inventory =
                                        ((Hopper) world.getBlockAt(8, 79, 8).getState())
                                                .getInventory();
                                for (ItemStack extracted : inventory.getContents())
                                    if (DiscDataUtil.read(extracted)
                                            .filter(expected::equals)
                                            .isPresent()) return true;
                                return false;
                            },
                            260,
                            () -> {
                                check(
                                        activeCount() == 0 && sourceCount() == 0,
                                        "natural finish cleans all audio sources");
                                cancellation();
                            });
                });
    }

    private void cancellation() {
        put(80, audio.replace("test.wav", "slow.wav"));
        waitFor(
                "slow audio load tracked",
                () -> activeCount() == 1,
                40,
                () -> {
                    ((Jukebox) world.getBlockAt(80, 80, 80).getState()).eject();
                    waitFor(
                            "ejection cancels pending load",
                            () -> activeCount() == 0,
                            40,
                            () ->
                                    later(
                                            85,
                                            () -> {
                                                check(
                                                        sourceCount() == 0,
                                                        "late load cannot recreate removed source");
                                                chunkUnload();
                                            }));
                });
    }

    private void chunkUnload() {
        put(160, audio.replace("test.wav", "stall.wav"));
        waitFor(
                "unload fixture starts",
                () -> activeCount() == 1,
                40,
                () -> {
                    world.unloadChunk(10, 10, false);
                    waitFor(
                            "fixture chunk unloads",
                            () -> !world.isChunkLoaded(10, 10),
                            300,
                            () -> {
                                check(
                                        activeCount() == 0 && sourceCount() == 0,
                                        "chunk unload stops audio immediately");
                                loadFailure();
                            });
                });
    }

    private void loadFailure() {
        put(16, audio.replace("test.wav", "missing.wav"));
        waitFor(
                "invalid audio begins resolving",
                () -> activeCount() == 1,
                40,
                () ->
                        waitFor(
                                "failed load releases its source",
                                () -> activeCount() == 0 && sourceCount() == 0,
                                180,
                                () -> {
                                    Jukebox jukebox =
                                            (Jukebox) world.getBlockAt(16, 80, 16).getState();
                                    check(
                                            !jukebox.isPlaying()
                                                    && DiscDataUtil.read(jukebox.getRecord())
                                                            .isPresent(),
                                            "failed track stops redstone without deleting disc");
                                    cancelledHopper();
                                }));
    }

    private void cancelledHopper() {
        Jukebox jukebox = box(24, audio);
        var source = Bukkit.createInventory(null, 9);
        var event =
                new InventoryMoveItemEvent(
                        source, jukebox.getRecord(), jukebox.getInventory(), true);
        event.setCancelled(true);
        Bukkit.getPluginManager().callEvent(event);
        later(
                3,
                () -> {
                    check(
                            activeCount() == 0 && sourceCount() == 0,
                            "cancelled hopper event cannot start playback");
                    worldUnload();
                });
    }

    private void worldUnload() {
        World original = world;
        world = otherWorld;
        put(0, audio.replace("test.wav", "stall.wav"));
        waitFor(
                "custom world track starts",
                () -> activeCount() == 1,
                40,
                () -> {
                    check(Bukkit.unloadWorld(otherWorld, false), "custom world unloads");
                    check(
                            activeCount() == 0 && sourceCount() == 0,
                            "world unload releases all its audio");
                    world = original;
                    disable();
                });
    }

    private void disable() {
        put(48, audio.replace("test.wav", "stall.wav"));
        waitFor(
                "disable fixture starts",
                () -> activeCount() == 1,
                40,
                () -> {
                    ServerSourceLine line = (ServerSourceLine) field(voice, "line");
                    Bukkit.getPluginManager().disablePlugin(plugin);
                    check(
                            activeCount() == 0 && line.getSources().isEmpty(),
                            "plugin disable releases all audio sources");
                    check(
                            getServer().getCommandMap().getCommand("lazodisc") == null,
                            "disable unregisters command aliases");
                    check(
                            ((Map<?, ?>) field(field(plugin, "commands"), "pending")).isEmpty(),
                            "plugin disable clears pending commands");
                    later(
                            2,
                            () -> {
                                check(
                                        Thread.getAllStackTraces().keySet().stream()
                                                .noneMatch(
                                                        thread ->
                                                                thread.isAlive()
                                                                        && thread.getName()
                                                                                .startsWith(
                                                                                        "LazoDiscs-Audio-Loader-")),
                                        "plugin disable stops loader workers");
                                finish(null);
                            });
                });
    }

    private Jukebox box(int coordinate, String url) {
        world.getChunkAt(coordinate >> 4, coordinate >> 4).load();
        var block = world.getBlockAt(coordinate, 80, coordinate);
        block.setType(Material.JUKEBOX);
        var jukebox = (Jukebox) block.getState();
        ItemStack item = new ItemStack(Material.MUSIC_DISC_CAT);
        DiscDataUtil.write(item, disc(url));
        jukebox.setRecord(item);
        jukebox.update();
        return (Jukebox) block.getState();
    }

    private void put(int coordinate, String url) {
        Jukebox jukebox = box(coordinate, url);
        Bukkit.getPluginManager()
                .callEvent(
                        new InventoryMoveItemEvent(
                                Bukkit.createInventory(null, 9),
                                jukebox.getRecord(),
                                jukebox.getInventory(),
                                true));
    }

    private CustomDiscData disc(String url) {
        return new CustomDiscData(url, "Fixture", 64, 1, UUID.randomUUID());
    }

    private int activeCount() {
        return ((Map<?, ?>) field(field(plugin, "playback"), "active")).size();
    }

    private int sourceCount() {
        return ((ServerSourceLine) field(voice, "line")).getSources().size();
    }

    private static Object field(Object object, String name) {
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private void reject(String url, String label) {
        try {
            DiscDataUtil.validateUrl(url);
            throw new AssertionError(label + ": accepted " + url);
        } catch (IllegalArgumentException expected) {
            check(true, label);
        }
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        passed.add(message);
        getLogger().info("PASS " + message);
    }

    private void waitFor(
            String description, BooleanSupplier condition, int remaining, Checked next) {
        if (condition.getAsBoolean()) {
            check(true, description);
            guarded(next);
        } else if (remaining == 0) finish(new AssertionError("Timed out: " + description));
        else later(1, () -> waitFor(description, condition, remaining - 1, next));
    }

    private void later(long ticks, Checked action) {
        Bukkit.getScheduler().runTaskLater(this, () -> guarded(action), ticks);
    }

    private void guarded(Checked action) {
        if (!finished)
            try {
                action.run();
            } catch (Throwable error) {
                finish(error);
            }
    }

    private void finish(Throwable error) {
        if (finished) return;
        finished = true;
        if (error != null)
            getLogger().log(java.util.logging.Level.SEVERE, "Integration failed", error);
        try {
            Path output = Path.of(System.getProperty("lazodiscs.testResult"));
            String failure =
                    error == null
                            ? "null"
                            : "\""
                                    + error.toString()
                                            .replace("\\", "\\\\")
                                            .replace("\"", "\\\"")
                                            .replace("\n", " ")
                                    + "\"";
            Files.writeString(
                    output, "{\"passed\":" + passed.size() + ",\"failure\":" + failure + "}\n");
        } catch (Exception reportError) {
            getLogger()
                    .log(java.util.logging.Level.SEVERE, "Cannot write test result", reportError);
        }
        Bukkit.shutdown();
    }

    @FunctionalInterface
    private interface Checked {
        void run() throws Exception;
    }
}
