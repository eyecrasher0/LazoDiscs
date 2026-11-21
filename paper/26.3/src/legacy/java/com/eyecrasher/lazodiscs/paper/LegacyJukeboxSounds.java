package com.eyecrasher.lazodiscs.paper;

import com.eyecrasher.lazodiscs.LazoDiscs;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Suppresses the native start effect while leaving the jukebox and transfer to the server. */
final class LegacyJukeboxSounds {
    private static final int RECORD_PLAY = 1010;
    private static final String HANDLER = "lazodiscs_jukebox";
    private final Map<UUID, Client> clients = new ConcurrentHashMap<>();
    private final LevelEvent effect;
    private volatile boolean closed;

    LegacyJukeboxSounds(LazoDiscs plugin, Listener listener, Consumer<Block> changed) {
        LevelEvent resolved = null;
        ClassLoader server = Jukebox.class.getClassLoader();
        try {
            Class.forName("org.bukkit.inventory.JukeboxInventory", false, server);
            LevelEvent event = new LevelEvent(server);
            Class<? extends Event> eventClass = Class.forName("org.bukkit.event.world.GenericGameEvent", false, server)
                    .asSubclass(Event.class);
            Method gameEvent = eventClass.getMethod("getEvent");
            Method location = eventClass.getMethod("getLocation");
            Object blockChange = Class.forName("org.bukkit.GameEvent", false, server).getField("BLOCK_CHANGE").get(null);
            // The native jukebox emits BLOCK_CHANGE after storing the item and before playing it.
            // Hopper event optimizations and cancelled sculk notifications do not change this order.
            plugin.getServer().getPluginManager().registerEvent(eventClass, listener, EventPriority.MONITOR, (owner, change) -> {
                try {
                    if (!change.isAsynchronous() && gameEvent.invoke(change) == blockChange)
                        changed.accept(((Location) location.invoke(change)).getBlock());
                } catch (ReflectiveOperationException failure) {
                    throw new org.bukkit.event.EventException(failure);
                }
            }, plugin, false);
            resolved = event;
        } catch (ClassNotFoundException failure) {
            if (!"org.bukkit.inventory.JukeboxInventory".equals(failure.getMessage()))
                LazoDiscs.LOGGER.warn("Could not bind native jukebox changes", failure);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            LazoDiscs.LOGGER.warn("Could not bind native jukebox changes", failure);
        }
        effect = resolved;
    }

    void track(Player player) {
        if (effect == null || closed) return;
        UUID playerId = player.getUniqueId();
        UUID world = player.getWorld().getUID();
        Client existing = clients.get(playerId);
        if (existing != null) {
            existing.world = world;
            return;
        }
        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            Object listener = field(handle.getClass(), type -> named(type, "ServerGamePacketListenerImpl", "PlayerConnection")).get(handle);
            Object connection = field(listener.getClass(), type -> named(type, "Connection", "NetworkManager")).get(listener);
            Channel channel = (Channel) field(connection.getClass(), Channel.class::isAssignableFrom).get(connection);
            Client client = new Client(channel, connection, effect.sender(connection), world);
            if (clients.putIfAbsent(playerId, client) != null) return;
            if (closed) {
                clients.remove(playerId, client);
                return;
            }
            client.onChannel(() -> {
                if (clients.get(playerId) != client) return;
                if (channel.pipeline().get(HANDLER) == null && channel.pipeline().get("packet_handler") != null) {
                    channel.pipeline().addBefore("packet_handler", HANDLER, client);
                } else {
                    clients.remove(playerId, client);
                }
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

    Marker expect(Block block) {
        Marker marker = marker(block);
        if (marker != null) for (Client client : marker.clients)
            client.fence(() -> client.pending.put(marker.position, marker));
        return marker;
    }

    void clear(Marker marker) {
        for (Client client : marker.clients) client.fence(() -> {
            if (client.pending.get(marker.position) == marker) client.pending.remove(marker.position);
        });
    }

    void forget(Block block) {
        Marker marker = marker(block);
        if (marker != null) for (Client client : marker.clients)
            client.fence(() -> client.pending.remove(marker.position));
    }

    private Marker marker(Block block) {
        if (clients.isEmpty()) return null;
        UUID world = block.getWorld().getUID();
        return new Marker(pack(block), clients.values().stream().filter(client -> client.world.equals(world)).toList());
    }

    private static long pack(Block block) {
        return ((block.getX() & 0x3ffffffL) << 38)
                | ((block.getZ() & 0x3ffffffL) << 12)
                | (block.getY() & 0xfffL);
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

    record Marker(long position, List<Client> clients) {}

    private static final class LevelEvent {
        private final Class<?> packet;
        private final Constructor<?> constructor;
        private final Object origin;
        private final Field id;
        private final Field position;
        private final Method packedPosition;

        LevelEvent(ClassLoader server) throws ReflectiveOperationException {
            String prefix = "net.minecraft.network.protocol.game.";
            Class<?> type;
            try {
                type = Class.forName(prefix + "ClientboundLevelEventPacket", false, server);
            } catch (ClassNotFoundException unmapped) {
                type = Class.forName(prefix + "PacketPlayOutWorldEvent", false, server);
            }
            packet = type;
            position = field(type, value -> named(value, "BlockPos", "BlockPosition"));
            Class<?> pos = position.getType();
            packedPosition = pos.getMethod(pos.getSimpleName().equals("BlockPos") ? "asLong" : "a");
            constructor = type.getConstructor(int.class, pos, int.class, boolean.class);
            origin = pos.getConstructor(int.class, int.class, int.class).newInstance(0, 0, 0);
            Object probe = constructor.newInstance(RECORD_PLAY, origin, 0, false);
            id = eventId(type, probe);
        }

        private static Field eventId(Class<?> type, Object probe) throws ReflectiveOperationException {
            // The static codec was added before the event ID in newer obfuscated servers.
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != int.class) continue;
                field.setAccessible(true);
                if (field.getInt(probe) == RECORD_PLAY) return field;
            }
            throw new NoSuchFieldException("Level event ID in " + type.getName());
        }

        Method sender(Object connection) throws ReflectiveOperationException {
            Class<?> base = Class.forName("net.minecraft.network.protocol.Packet", false, packet.getClassLoader());
            try {
                return connection.getClass().getMethod("send", base);
            } catch (NoSuchMethodException unmapped) {
                return connection.getClass().getMethod("a", base);
            }
        }

        Object fence() throws ReflectiveOperationException {
            return constructor.newInstance(0, origin, 0, false);
        }
    }

    private final class Client extends ChannelOutboundHandlerAdapter {
        private final Channel channel;
        private final Object connection;
        private final Method send;
        private final Map<Long, Marker> pending = new HashMap<>();
        private final Map<Object, Runnable> fences = new ConcurrentHashMap<>();
        private volatile UUID world;

        Client(Channel channel, Object connection, Method send, UUID world) {
            this.channel = channel;
            this.connection = connection;
            this.send = send;
            this.world = world;
        }

        void onChannel(Runnable action) {
            if (!channel.isOpen()) return;
            try { channel.eventLoop().execute(action); }
            catch (RejectedExecutionException closed) { /* Connection is shutting down. */ }
        }

        void fence(Runnable update) {
            if (closed || !channel.isOpen()) return;
            Object packet = null;
            try {
                // Use the same native send queue and packet type as the sound. Anti-Xray
                // can delay that queue after the current tick. This marker is consumed
                // here before encoding and has a harmless event ID if the handler detaches.
                packet = effect.fence();
                fences.put(packet, update);
                send.invoke(connection, packet);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                if (packet != null) fences.remove(packet);
                LazoDiscs.LOGGER.warn("Could not queue a jukebox sound marker", failure);
            }
        }

        void detach() {
            onChannel(() -> {
                if (channel.pipeline().context(this) != null) channel.pipeline().remove(this);
                pending.clear();
                fences.clear();
            });
        }

        @Override public void write(ChannelHandlerContext context, Object packet, ChannelPromise promise) throws Exception {
            if (effect.packet == packet.getClass()) {
                Runnable fence = fences.remove(packet);
                if (fence != null) {
                    fence.run();
                    promise.trySuccess();
                    return;
                }
                if (!pending.isEmpty() && effect.id.getInt(packet) == RECORD_PLAY) {
                    long position = (long) effect.packedPosition.invoke(effect.position.get(packet));
                    if (pending.remove(position) != null) {
                        promise.trySuccess();
                        return;
                    }
                }
            }
            context.write(packet, promise);
        }
    }
}
