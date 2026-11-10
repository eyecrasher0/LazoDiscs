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
        world = Bukkit.getWorlds().get(0);
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
        legacyInteractions();
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
        decodeAudio();
    }

    private void legacyInteractions() {
        var manager =
                (com.eyecrasher.lazodiscs.paper.JukeboxPlaybackManager) field(plugin, "playback");
        var block = world.getBlockAt(8, 80, 8);
        block.setType(Material.JUKEBOX);
        ItemStack[] held = {new ItemStack(Material.MUSIC_DISC_CAT)};
        var expected = disc(audio);
        DiscDataUtil.write(held[0], expected);
        UUID id = UUID.randomUUID();
        boolean[] permitted = {true};
        var inventory =
                (org.bukkit.inventory.PlayerInventory)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {org.bukkit.inventory.PlayerInventory.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "getItemInMainHand" -> held[0];
                                            case "setItemInMainHand" -> {
                                                held[0] = (ItemStack) args[0];
                                                yield null;
                                            }
                                            default -> null;
                                        });
        var player =
                (org.bukkit.entity.Player)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {org.bukkit.entity.Player.class},
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "getUniqueId" -> id;
                                            case "hasPermission" -> permitted[0];
                                            case "getInventory" -> inventory;
                                            case "getGameMode" -> org.bukkit.GameMode.SURVIVAL;
                                            default -> null;
                                        });
        var protectedEvent =
                interaction(player, block, held[0], org.bukkit.inventory.EquipmentSlot.HAND);
        protectedEvent.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        manager.insert(protectedEvent);
        check(
                ((Jukebox) block.getState()).getRecord().getType().isAir()
                        && held[0].getAmount() == 1,
                "protected legacy interaction cannot insert or consume a disc");
        permitted[0] = false;
        manager.insert(
                interaction(player, block, held[0], org.bukkit.inventory.EquipmentSlot.HAND));
        check(
                ((Jukebox) block.getState()).getRecord().getType().isAir()
                        && held[0].getAmount() == 1,
                "legacy playback permission is enforced before insertion");
        permitted[0] = true;
        manager.insert(
                interaction(player, block, held[0], org.bukkit.inventory.EquipmentSlot.HAND));
        check(held[0].getAmount() == 0, "legacy insertion consumes exactly one survival disc");
        check(
                DiscDataUtil.read(((Jukebox) block.getState()).getRecord())
                        .orElseThrow()
                        .equals(expected),
                "manual insertion preserves the full recording");
        var duplicate =
                interaction(player, block, null, org.bukkit.inventory.EquipmentSlot.OFF_HAND);
        manager.insert(duplicate);
        check(
                duplicate.isCancelled(),
                "second-hand event cannot immediately eject a newly inserted disc");
        ((Jukebox) block.getState()).eject();
        manager.validate();
        check(
                activeCount() == 0 && sourceCount() == 0,
                "manual insertion and ejection leave no audio source");
    }

    private org.bukkit.event.player.PlayerInteractEvent interaction(
            org.bukkit.entity.Player player,
            org.bukkit.block.Block block,
            ItemStack item,
            org.bukkit.inventory.EquipmentSlot hand) {
        return new org.bukkit.event.player.PlayerInteractEvent(
                player,
                org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                item,
                block,
                org.bukkit.block.BlockFace.UP,
                hand);
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
        check(count >= 13, "all vanilla music discs covered (" + count + ")");
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
        new Thread(
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
                                            || (stream.track().getState()
                                                            == AudioTrackState.INACTIVE
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
                                                                    "real HTTP WAV decoded and"
                                                                        + " encoded to Opus ("
                                                                            + decoded
                                                                            + " frames)");
                                                            hopperOrLegacyPlayback();
                                                        }));
                            } catch (Throwable error) {
                                plugin.onMain(() -> finish(error));
                            }
                        },
                        "LazoDiscs-Test-Decode")
                .start();
    }

    private void hopperOrLegacyPlayback() throws Exception {
        try {
            Jukebox.class.getMethod("getInventory");
        } catch (NoSuchMethodException oldVanilla) {
            legacyPlayback();
            return;
        }
        world.setChunkForceLoaded(0, 0, true);
        world.getBlockAt(8, 80, 8).setType(Material.JUKEBOX);
        world.getBlockAt(8, 81, 8).setType(Material.HOPPER);
        world.getBlockAt(8, 79, 8).setType(Material.HOPPER);
        ItemStack item = new ItemStack(Material.MUSIC_DISC_CAT);
        DiscDataUtil.write(item, disc(audio));
        ((Hopper) world.getBlockAt(8, 81, 8).getState()).getInventory().setItem(0, item);
        waitFor(
                "native legacy hopper inserts and starts audio",
                () -> activeCount() == 1,
                160,
                () -> {
                    check(
                            ((Hopper) world.getBlockAt(8, 79, 8).getState())
                                    .getInventory()
                                    .isEmpty(),
                            "legacy hopper cannot extract a disc during custom playback");
                    waitFor(
                            "legacy track completes",
                            () -> activeCount() == 0 && sourceCount() == 0,
                            180,
                            () ->
                                    waitFor(
                                            "native legacy hopper extracts after completion",
                                            () ->
                                                    !((Hopper)
                                                                    world.getBlockAt(8, 79, 8)
                                                                            .getState())
                                                            .getInventory()
                                                            .isEmpty(),
                                            160,
                                            this::lateEjection));
                });
    }

    private void legacyPlayback() {
        put(8, audio);
        waitFor(
                "legacy insertion starts positional source",
                () -> activeCount() == 1,
                100,
                () -> {
                    check(sourceCount() == 1, "one Plasmo source for legacy jukebox");
                    waitFor(
                            "track completes and source is released",
                            () -> activeCount() == 0 && sourceCount() == 0,
                            180,
                            () -> {
                                check(
                                        DiscDataUtil.read(
                                                        ((Jukebox)
                                                                        world.getBlockAt(8, 80, 8)
                                                                                .getState())
                                                                .getRecord())
                                                .isPresent(),
                                        "finished disc is retained");
                                lateEjection();
                            });
                });
    }

    private void lateEjection() {
        put(16, audio.replace("test.wav", "slow.wav"));
        waitFor(
                "slow legacy load starts",
                () -> activeCount() == 1,
                80,
                () -> {
                    ((Jukebox) world.getBlockAt(16, 80, 16).getState()).eject();
                    waitFor(
                            "ejection clears pending legacy audio",
                            () -> activeCount() == 0 && sourceCount() == 0,
                            100,
                            () ->
                                    later(
                                            100,
                                            () -> {
                                                check(
                                                        sourceCount() == 0,
                                                        "late audio callback cannot resurrect"
                                                            + " ejected source");
                                                failureCleanup();
                                            }));
                });
    }

    private void failureCleanup() {
        put(24, audio.replace("test.wav", "missing.wav"));
        waitFor(
                "bad legacy URL starts loading",
                () -> activeCount() == 1,
                80,
                () ->
                        waitFor(
                                "failed legacy load releases source",
                                () -> activeCount() == 0 && sourceCount() == 0,
                                160,
                                this::worldUnload));
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
                        new org.bukkit.event.player.PlayerInteractEvent(
                                null,
                                org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK,
                                null,
                                jukebox.getBlock(),
                                org.bukkit.block.BlockFace.UP));
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
