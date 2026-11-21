package com.eyecrasher.lazodiscs.paper;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.data.CustomDiscData;
import com.eyecrasher.lazodiscs.data.DiscDataUtil;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import com.eyecrasher.lazodiscs.voice.LazoDiscsVoiceAddon;

import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Jukebox;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/** All world and inventory access in this class runs on the Paper server thread. */
public final class JukeboxPlaybackManager implements Listener {
    private final LazoDiscs plugin;
    private final LazoDiscsVoiceAddon voice;
    private final Map<Key, Entry> active = new HashMap<>();
    private final Map<Key, Entry> completed = new HashMap<>();
    private final Map<UUID, Key> insertedThisTick = new HashMap<>();
    private final LegacyJukeboxSounds sounds = new LegacyJukeboxSounds();

    public JukeboxPlaybackManager(LazoDiscs plugin, LazoDiscsVoiceAddon voice) {
        this.plugin = plugin;
        this.voice = voice;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void checkPermission(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.JUKEBOX
                || event.useInteractedBlock() == Event.Result.DENY
                || event.useItemInHand() == Event.Result.DENY) return;
        Jukebox jukebox = (Jukebox) event.getClickedBlock().getState();
        if (jukebox.getRecord().getType().isAir()
                && DiscDataUtil.read(event.getItem()).isPresent()
                && !event.getPlayer().hasPermission("lazodiscs.play")) {
            event.setCancelled(true);
            event.getPlayer()
                    .sendMessage(LazoDiscsText.playNoPermission().color(NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void insert(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null
                || event.getClickedBlock().getType() != Material.JUKEBOX) return;
        Key key = Key.of(event.getClickedBlock());
        if (event.getHand() == EquipmentSlot.OFF_HAND
                && key.equals(insertedThisTick.get(event.getPlayer().getUniqueId()))) {
            event.setCancelled(true);
            return;
        }
        if (event.useInteractedBlock() == Event.Result.DENY
                || event.useItemInHand() == Event.Result.DENY
                || event.getHand() == null) return;
        Jukebox jukebox = (Jukebox) event.getClickedBlock().getState();
        ItemStack held = event.getItem();
        if (!jukebox.getRecord().getType().isAir() || DiscDataUtil.read(held).isEmpty()) return;
        if (!event.getPlayer().hasPermission("lazodiscs.play")) return;
        event.setCancelled(true);
        ItemStack inserted = held.clone();
        inserted.setAmount(1);
        jukebox.setRecord(inserted);
        if (!updateWithoutNativeSong(jukebox)) return;
        // Send the stop in the same tick as insertion, before a client can start the vanilla song.
        stopVanilla(jukebox);
        if (event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            ItemStack remaining = held.clone();
            remaining.setAmount(held.getAmount() - 1);
            if (event.getHand() == EquipmentSlot.HAND)
                event.getPlayer().getInventory().setItemInMainHand(remaining);
            else event.getPlayer().getInventory().setItemInOffHand(remaining);
        }
        retireCompleted(Key.of(jukebox.getBlock()));
        if (event.getHand() == EquipmentSlot.HAND) {
            UUID player = event.getPlayer().getUniqueId();
            insertedThisTick.put(player, key);
            plugin.onMain(() -> insertedThisTick.remove(player, key));
        }
        sync(jukebox.getBlock(), true);
    }

    private static boolean updateWithoutNativeSong(Jukebox jukebox) {
        try {
            Class<?> implementation = jukebox.getClass()
                    .getMethod("update", boolean.class, boolean.class).getDeclaringClass();
            if (!Jukebox.class.isAssignableFrom(implementation)) return jukebox.update();
            // CraftJukebox.update emits RECORD_PLAY after storing the snapshot. Its immediate
            // stop can miss a sound that the client is still loading, leaving an orphan song.
            // Commit through the block-entity implementation, retaining native item/state
            // persistence and physics without starting a vanilla song in the first place.
            var update = MethodHandles.privateLookupIn(implementation, MethodHandles.lookup())
                    .findSpecial(implementation.getSuperclass(), "update",
                            MethodType.methodType(boolean.class, boolean.class, boolean.class),
                            implementation);
            return (boolean) update.invoke(jukebox, false, true);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot store a custom disc without starting its vanilla song", failure);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void interact(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.JUKEBOX
                && event.useInteractedBlock() != Event.Result.DENY) {
            Block block = event.getClickedBlock();
            sounds.forget(block);
            scheduleSync(block);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void retainWhilePlaying(InventoryMoveItemEvent event) {
        if (event.getSource().getHolder() instanceof Jukebox jukebox
                && active.containsKey(Key.of(jukebox.getBlock()))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hopper(InventoryMoveItemEvent event) {
        scheduleInventory(event.getSource());
        if (!(event.getDestination().getHolder() instanceof Jukebox jukebox)) return;
        Block block = jukebox.getBlock();
        var marker = jukebox.getRecord().getType().isAir() && DiscDataUtil.read(event.getItem()).isPresent()
                ? sounds.expect(block, event) : null;
        // A refused move must not suppress a later ordinary start at the same jukebox.
        if (marker == null) sounds.forget(block);
        scheduleSync(block, marker);
    }

    private void scheduleInventory(Inventory inventory) {
        if (inventory.getHolder() instanceof Jukebox jukebox) scheduleSync(jukebox.getBlock());
    }

    @EventHandler public void join(PlayerJoinEvent event) { sounds.track(event.getPlayer()); }

    @EventHandler public void changeWorld(PlayerChangedWorldEvent event) { sounds.track(event.getPlayer()); }

    @EventHandler public void quit(PlayerQuitEvent event) { sounds.untrack(event.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void breakBlock(BlockBreakEvent event) {
        stop(Key.of(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void explode(BlockExplodeEvent event) {
        event.blockList().forEach(block -> stop(Key.of(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void explode(EntityExplodeEvent event) {
        event.blockList().forEach(block -> stop(Key.of(block)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void place(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == Material.JUKEBOX)
            scheduleSync(event.getBlockPlaced());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void load(ChunkLoadEvent event) {
        // The extra tick lets other plugins finish loading block entities first.
        Chunk chunk = event.getChunk();
        plugin.onMain(
                () -> {
                    if (chunk.isLoaded()) resume(chunk);
                });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void unload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        stopMatching(
                key ->
                        key.world.equals(chunk.getWorld().getUID())
                                && (key.x >> 4) == chunk.getX()
                                && (key.z >> 4) == chunk.getZ());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void unload(WorldUnloadEvent event) {
        stopMatching(key -> key.world.equals(event.getWorld().getUID()));
    }

    public void resumeLoadedChunks() {
        for (var player : Bukkit.getOnlinePlayers()) plugin.onMain(() -> sounds.track(player));
        for (World world : Bukkit.getWorlds())
            for (Chunk chunk : world.getLoadedChunks()) resume(chunk);
    }

    private void resume(Chunk chunk) {
        if (!voice.isReady()) return;
        for (BlockState tile : chunk.getTileEntities()) {
            if (tile instanceof Jukebox jukebox
                    && DiscDataUtil.read(jukebox.getRecord()).isPresent())
                sync(jukebox.getBlock(), true);
        }
    }

    private void scheduleSync(Block block) {
        scheduleSync(block, null);
    }

    private void scheduleSync(Block block, LegacyJukeboxSounds.Marker marker) {
        Key key = Key.of(block);
        plugin.onMain(
                () -> {
                    Block loaded = key.loadedBlock();
                    if (marker != null) sounds.clear(marker);
                    if (loaded != null) sync(loaded, false);
                });
    }

    public void close() {
        sounds.close();
        stopAll();
    }

    private void sync(Block block, boolean resume) {
        Key key = Key.of(block);
        if (!(block.getState() instanceof Jukebox jukebox)) {
            stop(key);
            return;
        }
        CustomDiscData disc = DiscDataUtil.read(jukebox.getRecord()).orElse(null);
        Entry old = active.get(key);
        if (disc == null) {
            retireCompleted(key);
            stop(key);
            return;
        }
        if (old != null && old.disc.equals(disc)) return;
        Entry ended = completed.get(key);
        if (ended != null && disc.id().equals(ended.disc.id())) return;
        stop(key);
        retireCompleted(key);
        if (!voice.isReady()) {
            stopVanilla(jukebox);
            return;
        }
        try {
            DiscDataUtil.validateUrl(disc.url());
            stopVanilla(jukebox);
            Entry entry = new Entry(disc);
            entry.session =
                    voice.create(block.getLocation(), disc, failure -> finish(key, entry, failure));
            active.put(key, entry);
            entry.session.start();
        } catch (Exception | LinkageError error) {
            stop(key);
            stopVanilla(jukebox);
            notifyFailure(block.getLocation(), disc, LazoDiscsVoiceAddon.messageOf(error));
            LazoDiscs.LOGGER.warn(
                    "Could not start disc at {}: {}", block.getLocation(), error.toString());
        }
    }

    private void finish(Key key, Entry expected, String failure) {
        if (!active.remove(key, expected)) return;
        completed.put(key, expected);
        Block block = key.loadedBlock();
        if (block != null
                && block.getState() instanceof Jukebox jukebox
                && DiscDataUtil.read(jukebox.getRecord())
                        .filter(expected.disc::equals)
                        .isPresent()) {
            stopVanilla(jukebox);
            if (failure != null) notifyFailure(block.getLocation(), expected.disc, failure);
        }
    }

    private void stopVanilla(Jukebox jukebox) {
        jukebox.stopPlaying();
        // CraftBukkit sends the version-appropriate stop event. A manual RECORD_PLAY(0)
        // starts song registry entry zero on modern clients instead of stopping it.
        // Keep neighboring hopper state synchronized after the native playing flag changes.
        for (var face :
                java.util.List.of(
                        org.bukkit.block.BlockFace.DOWN,
                        org.bukkit.block.BlockFace.UP,
                        org.bukkit.block.BlockFace.NORTH,
                        org.bukkit.block.BlockFace.SOUTH,
                        org.bukkit.block.BlockFace.EAST,
                        org.bukkit.block.BlockFace.WEST)) {
            Block neighbor = jukebox.getBlock().getRelative(face);
            syncHopper(neighbor);
        }
    }

    private static void syncHopper(Block neighbor) {
        if (!neighbor.getWorld().isChunkLoaded(neighbor.getX() >> 4, neighbor.getZ() >> 4)) return;
        if (neighbor.getBlockData() instanceof org.bukkit.block.data.type.Hopper hopper) {
            boolean enabled = !neighbor.isBlockIndirectlyPowered();
            if (hopper.isEnabled() != enabled) {
                hopper.setEnabled(enabled);
                neighbor.setBlockData(hopper, true);
            }
        }
    }

    private void notifyFailure(Location location, CustomDiscData disc, String reason) {
        double radius = Math.max(32, Math.min(disc.range(), 256));
        location.getWorld()
                .getNearbyPlayers(location.clone().add(0.5, 0.5, 0.5), radius)
                .forEach(
                        player ->
                                player.sendMessage(
                                        LazoDiscsText.audioLoadFailed(disc.title(), reason)
                                                .color(NamedTextColor.RED)));
    }

    /** Validate only active sources; never load chunks or scan the world on a timer. */
    public void validate() {
        completed.entrySet().removeIf(item -> {
            Block block = item.getKey().loadedBlock();
            if (block != null && block.getState() instanceof Jukebox jukebox
                    && DiscDataUtil.read(jukebox.getRecord()).filter(item.getValue().disc::equals).isPresent()) return false;
            item.getValue().session.stop();
            return true;
        });
        var iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            var item = iterator.next();
            Block block = item.getKey().loadedBlock();
            if (block == null
                    || !(block.getState() instanceof Jukebox jukebox)
                    || DiscDataUtil.read(jukebox.getRecord())
                            .filter(item.getValue().disc::equals)
                            .isEmpty()) {
                iterator.remove();
                item.getValue().session.stop();
            }
        }
    }

    private void stop(Key key) {
        Entry entry = active.remove(key);
        if (entry != null) entry.session.stop();
        Entry ended = completed.get(key);
        if (ended != null) ended.session.stop();
    }

    private void retireCompleted(Key key) {
        Entry ended = completed.remove(key);
        if (ended != null) ended.session.stop();
    }

    private void stopMatching(java.util.function.Predicate<Key> predicate) {
        completed.entrySet().removeIf(entry -> {
            if (!predicate.test(entry.getKey())) return false;
            entry.getValue().session.stop();
            return true;
        });
        var iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            var item = iterator.next();
            if (predicate.test(item.getKey())) {
                iterator.remove();
                item.getValue().session.stop();
            }
        }
    }

    public void stopAll() {
        active.values().forEach(entry -> entry.session.stop());
        active.clear();
        for (Entry ended : completed.values()) ended.session.stop();
        completed.clear();
        insertedThisTick.clear();
    }

    private static final class Entry {
        private final CustomDiscData disc;
        private LazoDiscsVoiceAddon.Session session;

        private Entry(CustomDiscData disc) {
            this.disc = disc;
        }
    }

    private record Key(UUID world, int x, int y, int z) {
        static Key of(Block block) {
            return new Key(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        Block loadedBlock() {
            World loaded = Bukkit.getWorld(world);
            return loaded == null || !loaded.isChunkLoaded(x >> 4, z >> 4)
                    ? null
                    : loaded.getBlockAt(x, y, z);
        }
    }
}
