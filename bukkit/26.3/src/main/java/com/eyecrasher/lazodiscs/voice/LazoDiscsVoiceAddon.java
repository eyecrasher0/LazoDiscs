package com.eyecrasher.lazodiscs.voice;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.eyecrasher.lazodiscs.data.CustomDiscData;
import java.util.ArrayList;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** One Bukkit entry point selects the installed Voice generation without linking absent APIs. */
public final class LazoDiscsVoiceAddon implements Listener, PluginMessageListener {
    private final LazoDiscs plugin;
    private final YoutubeLoginLogFilter logs = new YoutubeLoginLogFilter(message -> LazoDiscs.LOGGER.warn("{}", message));
    private final java.util.Map<UUID, Player> legacyClients = new ConcurrentHashMap<>();
    private Plugin legacyVoice;
    private Set<Player> legacySessions = Set.of();
    private volatile Pv2Transport transport;
    private Object addon;
    private volatile boolean legacy, loadedAudio;

    public LazoDiscsVoiceAddon(LazoDiscs plugin) { this.plugin = plugin; }
    public void load() {
        var voice = plugin.getServer().getPluginManager().getPlugin("PlasmoVoice");
        if (voice == null) throw new IllegalStateException("Plasmo Voice is required");
        legacy = voice.getDescription().getVersion().startsWith("1.");
        logs.install();
        StreamingSession.enable();
        if (legacy) {
            legacyVoice = voice;
            return;
        }
        addon = new Addon(this);
        Object loader = loader();
        Pv2Transport.call(loader, "load", addon);
    }
    public void enable() {
        if (!legacy) return;
        try {
            @SuppressWarnings("unchecked")
            var connected = (Set<Player>) ReflectiveApi.call(legacyVoice, "getConnectedPlayers");
            if (connected != null) legacySessions = connected;
        } catch (RuntimeException | LinkageError error) {
            LazoDiscs.LOGGER.warn("Could not read Plasmo Voice 1 UDP sessions", error);
        }
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, LegacyAudioPacket.CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, LegacyAudioPacket.CHANNEL);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        LazoDiscs.LOGGER.info("Voice 1 support ready; listeners need LazoDiscs on their client");
    }
    public void unload() {
        try {
            StreamingSession.shutdown();
            if (addon != null) Pv2Transport.call(loader(), "unload", addon);
            legacyClients.clear();
            legacySessions = Set.of();
            plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin);
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin);
        } finally { logs.close(); transport = null; }
    }
    private Object loader() {
        try { return Class.forName("su.plo.voice.api.server.PlasmoVoiceServer").getMethod("getAddonsLoader").invoke(null); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Plasmo Voice 2 addon API unavailable", error); }
    }
    public boolean isReady() { return legacy || transport != null; }
    public boolean hasLoadedAudio() { return loadedAudio; }
    public void markAudioUsed() { loadedAudio = true; }
    public Session create(Location location, CustomDiscData disc, Consumer<String> finished) {
        StreamingSession.Sink sink;
        if (!legacy) {
            Pv2Transport current = transport;
            if (current == null) throw new IllegalStateException("Plasmo Voice is not ready");
            sink = current.source(location.getWorld(), location.getX() + .5, location.getY() + .5, location.getZ() + .5, disc.title(), disc.range());
        } else {
            UUID source = UUID.randomUUID();
            var audience = new LegacyAudience();
            sink = new LegacyPacketSender(source, task -> plugin.onLegacyRegion(location, task),
                    (opus, sequence) -> LegacyAudioPacket.frame(source,
                            com.eyecrasher.lazodiscs.bukkit.Messages.worldKey(location.getWorld()),
                            location.getX() + .5, location.getY() + .5, location.getZ() + .5,
                            disc.range(), sequence, opus),
                    packet -> {
                        var audible = new ArrayList<UUID>();
                        if (legacyVoice.isEnabled()) {
                            for (Player player : legacySessions) {
                                UUID id = player.getUniqueId();
                                if (!player.isOnline() || !legacyClients.containsKey(id)) continue;
                                if (packet.kind == LegacyAudioPacket.FRAME && player.getWorld().equals(location.getWorld())
                                        && player.getLocation().distanceSquared(location) <= (double) disc.range() * disc.range())
                                    audible.add(id);
                            }
                        }
                        audience.deliver(packet, audible, id -> {
                            Player online = legacyClients.get(id);
                            return online != null && online.isOnline() ? online : null;
                        }, (player, bytes) -> player.sendPluginMessage(plugin, LegacyAudioPacket.CHANNEL, bytes));
                    });
        }
        StreamingSession stream = new StreamingSession(sink, failure -> plugin.onRegion(location, () -> finished.accept(failure)));
        return new Session(stream, disc);
    }
    @Override public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!legacy || !channel.equals(LegacyAudioPacket.CHANNEL)) return;
        try { if (LegacyAudioPacket.decode(data).kind == LegacyAudioPacket.HELLO) legacyClients.put(player.getUniqueId(), player); }
        catch (java.io.IOException malformed) { /* Ignore unknown/unbounded client payloads. */ }
    }
    @EventHandler public void quit(PlayerQuitEvent event) { legacyClients.remove(event.getPlayer().getUniqueId()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void disabling(PluginDisableEvent event) {
        if (legacy && event.getPlugin() == plugin) plugin.prepareLegacyDisable();
    }
    public static String messageOf(Throwable error) { return StreamingSession.messageOf(error); }
    public final class Session {
        private final StreamingSession stream;
        private final CustomDiscData disc;
        Session(StreamingSession stream, CustomDiscData disc) { this.stream = stream; this.disc = disc; }
        public void start() { loadedAudio = true; stream.start(disc); }
        public void stop() { stream.close(); }
    }

    @su.plo.voice.api.addon.annotation.Addon(id = "lazodiscs", scope = su.plo.voice.api.addon.AddonLoaderScope.SERVER, name = "LazoDiscs", version = "1.0.8", authors = {"EyeCrasher"})
    public static final class Addon implements su.plo.voice.api.addon.AddonInitializer {
        @su.plo.voice.api.addon.InjectPlasmoVoice
        @javax.inject.Inject
        private su.plo.voice.api.server.PlasmoVoiceServer server;
        private final LazoDiscsVoiceAddon owner;
        public Addon(LazoDiscsVoiceAddon owner) { this.owner = owner; }
        @Override public void onAddonInitialize() {
            owner.transport = new Pv2Transport(server, this);
            LazoDiscs.LOGGER.info("Plasmo Voice 2 Discs source line ready");
        }
        @Override public void onAddonShutdown() { owner.transport = null; owner.plugin.voiceStopped(); }
    }
}
