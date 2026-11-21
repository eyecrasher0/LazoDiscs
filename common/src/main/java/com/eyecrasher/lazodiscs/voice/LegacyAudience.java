package com.eyecrasher.lazodiscs.voice;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;

/** One source's recipients; terminal packets reach them even after they leave its range. */
public final class LegacyAudience {
    private final Set<UUID> listeners = new HashSet<>();

    public <T> void deliver(LegacyAudioPacket packet, Iterable<UUID> audible,
            Function<UUID, T> connected, BiConsumer<T, byte[]> send) {
        byte[] bytes = packet.encode();
        if (packet.kind == LegacyAudioPacket.FRAME) {
            listeners.removeIf(id -> connected.apply(id) == null);
            for (UUID id : audible) {
                T listener = connected.apply(id);
                if (listener != null) {
                    send.accept(listener, bytes);
                    listeners.add(id);
                }
            }
        } else {
            listeners.removeIf(id -> {
                T listener = connected.apply(id);
                if (listener != null) send.accept(listener, bytes);
                return listener == null || packet.kind == LegacyAudioPacket.STOP;
            });
        }
    }
}
