package com.eyecrasher.lazodiscs.voice;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

/** One source's recipients; terminal packets reach them even after they leave its range. */
public final class LegacyAudience {
    private final Set<UUID> listeners = new HashSet<>();

    public void deliver(LegacyAudioPacket packet, Iterable<UUID> audible, BiConsumer<UUID, byte[]> send) {
        byte[] bytes = packet.encode();
        if (packet.kind == LegacyAudioPacket.FRAME) {
            for (UUID listener : audible) {
                listeners.add(listener);
                send.accept(listener, bytes);
            }
        } else {
            for (UUID listener : listeners) send.accept(listener, bytes);
            if (packet.kind == LegacyAudioPacket.STOP) listeners.clear();
        }
    }
}
