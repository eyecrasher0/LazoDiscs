package com.eyecrasher.lazodiscs.paper;

import com.eyecrasher.lazodiscs.LazoDiscs;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Predicate;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryMoveItemEvent;

/** Filters the native start effect before encoding. The server still performs the entire item move. */
final class LegacyJukeboxSounds {
    private static final int RECORD_PLAY = 1010;
    private static final String HANDLER = "lazodiscs_jukebox";
    private static final boolean AUTOMATION = hasJukeboxInventory();
    private static final ClassValue<LevelEvent> EVENTS = new ClassValue<>() {
        @Override protected LevelEvent computeValue(Class<?> type) {
            boolean mapped = type.getSimpleName().equals("ClientboundLevelEventPacket");
            if (!mapped && !type.getSimpleName().equals("PacketPlayOutWorldEvent")) return null;
            try {
                Field id = type.getDeclaredField(mapped ? "type" : "a");
                id.setAccessible(true);
                Field position = field(type, value -> named(value, "BlockPos", "BlockPosition"));
                return new LevelEvent(id, position, position.getType().getMethod(mapped ? "asLong" : "a"));
            } catch (ReflectiveOperationException | RuntimeException failure) {
                LazoDiscs.LOGGER.warn("Could not bind the native jukebox start effect", failure);
                return null;
            }
        }
    };

    private final Map<UUID, Client> clients = new ConcurrentHashMap<>();
    private volatile boolean closed;

    void track(Player player) {
        if (!AUTOMATION || closed) return;
        UUID playerId = player.getUniqueId();
        UUID world = player.getWorld().getUID();
        Client existing = clients.get(playerId);
        if (existing != null) {
            existing.onChannel(() -> {
                if (!existing.world.equals(world)) {
                    existing.pending.clear();
                    existing.world = world;
                }
            });
            return;
        }
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object listener = field(handle.getClass(), type -> named(type, "ServerGamePacketListenerImpl", "PlayerConnection")).get(handle);
            Object connection = field(listener.getClass(), type -> named(type, "Connection", "NetworkManager")).get(listener);
            Channel channel = (Channel) field(connection.getClass(), Channel.class::isAssignableFrom).get(connection);
            Client client = new Client(channel, world);
            if (clients.putIfAbsent(playerId, client) != null) return;
            if (closed) {
                clients.remove(playerId, client);
                return;
            }
            client.onChannel(() -> {
                if (clients.get(playerId) == client && channel.pipeline().get(HANDLER) == null
                        && channel.pipeline().get("packet_handler") != null)
                    channel.pipeline().addBefore("packet_handler", HANDLER, client);
            });
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LazoDiscs.LOGGER.warn("Could not attach jukebox sound handling to {}", player.getName(), failure);
        }
    }

    void untrack(UUID id) {
        Client client = clients.remove(id);
        if (client != null) client.detach();
    }

    void close() {
        closed = true;
        clients.forEach((id, client) -> client.detach());
        clients.clear();
    }

    Marker expect(Block block, InventoryMoveItemEvent event) {
        var marker = new Marker(block.getWorld().getUID(), pack(block), event);
        // Queue the marker on the same event loop as the server's outbound packet.
        // Ordering survives a slow network thread, and the network thread never reads a world.
        for (Client client : clients.values()) client.onChannel(() -> {
            if (client.world.equals(marker.world)) client.pending.put(marker.position, marker);
        });
        return marker;
    }

    void clear(Marker marker) {
        for (Client client : clients.values())
            client.onChannel(() -> client.pending.remove(marker.position, marker));
    }

    void forget(Block block) {
        if (clients.isEmpty()) return;
        UUID world = block.getWorld().getUID();
        long position = pack(block);
        for (Client client : clients.values()) client.onChannel(() -> {
            if (client.world.equals(world)) client.pending.remove(position);
        });
    }

    private static long pack(Block block) {
        return ((block.getX() & 0x3ffffffL) << 38)
                | ((block.getZ() & 0x3ffffffL) << 12)
                | (block.getY() & 0xfffL);
    }

    private static boolean hasJukeboxInventory() {
        try {
            Class.forName("org.bukkit.inventory.JukeboxInventory", false, Jukebox.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException olderServer) {
            return false;
        }
    }

    private static boolean named(Class<?> type, String mojang, String spigot) {
        return type.getSimpleName().equals(mojang) || type.getSimpleName().equals(spigot);
    }

    private static Field field(Class<?> type, Predicate<Class<?>> accepts) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !accepts.test(field.getType())) continue;
                field.setAccessible(true);
                return field;
            }
        }
        throw new NoSuchFieldException(type.getName());
    }

    record Marker(UUID world, long position, InventoryMoveItemEvent event) {}
    private record LevelEvent(Field id, Field position, Method packedPosition) {}

    private static final class Client extends ChannelOutboundHandlerAdapter {
        private final Channel channel;
        private final Map<Long, Marker> pending = new HashMap<>();
        private UUID world;

        Client(Channel channel, UUID world) {
            this.channel = channel;
            this.world = world;
        }

        void onChannel(Runnable action) {
            if (!channel.isOpen()) return;
            try { channel.eventLoop().execute(action); }
            catch (RejectedExecutionException closed) { /* Connection is shutting down. */ }
        }

        void detach() {
            onChannel(() -> {
                if (channel.pipeline().context(this) != null) channel.pipeline().remove(this);
                pending.clear();
            });
        }

        @Override public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
            if (!pending.isEmpty()) {
                LevelEvent effect = EVENTS.get(packet.getClass());
                if (effect != null && effect.id.getInt(packet) == RECORD_PLAY) {
                    long position = (long) effect.packedPosition.invoke(effect.position.get(packet));
                    Marker marker = pending.remove(position);
                    if (marker != null && world.equals(marker.world) && !marker.event.isCancelled()) {
                        promise.setSuccess();
                        return;
                    }
                }
            }
            context.write(packet, promise);
        }
    }
}
