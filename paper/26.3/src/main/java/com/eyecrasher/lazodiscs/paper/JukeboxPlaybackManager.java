package com.eyecrasher.lazodiscs.paper;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.data.CustomDiscData;
import com.eyecrasher.lazodiscs.data.DiscDataUtil;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import com.eyecrasher.lazodiscs.voice.LazoDiscsVoiceAddon;

import net.kyori.adventure.text.format.NamedTextColor;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** All world and inventory access in this class runs on the Paper server thread. */
public final class JukeboxPlaybackManager implements Listener {
    private final LazoDiscs plugin;
    private final LazoDiscsVoiceAddon voice;
    private final Map<Key, Entry> active = new HashMap<>();

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
        if (!jukebox.hasRecord()
                && DiscDataUtil.read(event.getItem()).isPresent()
                && !event.getPlayer().hasPermission("lazodiscs.play")) {
            event.setCancelled(true);
            event.getPlayer()
                    .sendMessage(LazoDiscsText.playNoPermission().color(NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void interact(PlayerInteractEvent event) {
        // Block use and item use can be denied separately by protection plugins.
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK
                && event.getClickedBlock() != null
                && event.getClickedBlock().getType() == Material.JUKEBOX
                && event.useInteractedBlock() != Event.Result.DENY)
            scheduleSync(event.getClickedBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void hopper(InventoryMoveItemEvent event) {
        scheduleInventory(event.getSource());
        scheduleInventory(event.getDestination());
    }

    private void scheduleInventory(Inventory inventory) {
        if (inventory.getHolder(false) instanceof Jukebox jukebox) scheduleSync(jukebox.getBlock());
    }

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
        Key key = Key.of(block);
        plugin.onMain(
                () -> {
                    Block loaded = key.loadedBlock();
                    if (loaded != null) sync(loaded, false);
                });
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
            stop(key);
            return;
        }
        if (old != null && old.disc.equals(disc)) return;
        stop(key);
        if (!resume && !jukebox.isPlaying()) return;
        if (!voice.isReady()) {
            jukebox.stopPlaying();
            return;
        }
        try {
            DiscDataUtil.validateUrl(disc.url());
            if (!jukebox.isPlaying()) jukebox.startPlaying();
            Entry entry = new Entry(disc);
            entry.session =
                    voice.create(block.getLocation(), disc, failure -> finish(key, entry, failure));
            active.put(key, entry);
            entry.session.start();
        } catch (Exception | LinkageError error) {
            stop(key);
            jukebox.stopPlaying();
            notifyFailure(block.getLocation(), disc, LazoDiscsVoiceAddon.messageOf(error));
            LazoDiscs.LOGGER.warn(
                    "Could not start disc at {}: {}", block.getLocation(), error.toString());
        }
    }

    private void finish(Key key, Entry expected, String failure) {
        if (!active.remove(key, expected)) return;
        Block block = key.loadedBlock();
        if (block != null
                && block.getState() instanceof Jukebox jukebox
                && DiscDataUtil.read(jukebox.getRecord())
                        .filter(expected.disc::equals)
                        .isPresent()) {
            jukebox.stopPlaying();
            if (failure != null) notifyFailure(block.getLocation(), expected.disc, failure);
        }
    }

    private void notifyFailure(Location location, CustomDiscData disc, String reason) {
        double radius = Math.clamp(disc.range(), 32, 256);
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
        var iterator = active.entrySet().iterator();
        while (iterator.hasNext()) {
            var item = iterator.next();
            Block block = item.getKey().loadedBlock();
            if (block == null
                    || !(block.getState() instanceof Jukebox jukebox)
                    || !jukebox.isPlaying()
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
    }

    private void stopMatching(java.util.function.Predicate<Key> predicate) {
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
