package com.eyecrasher.lazodiscs.voice;

import com.eyecrasher.lazodiscs.LazoDiscs;
import java.util.Map;
import java.util.UUID;

final class LegacyVoiceSessions {
    private static final Map<?, ?> CLIENTS = findClients();

    private LegacyVoiceSessions() {}

    static boolean hasSession(UUID id) {
        return CLIENTS.containsKey(id);
    }

    private static Map<?, ?> findClients() {
        try {
            return (Map<?, ?>) Class.forName("su.plo.voice.server.socket.SocketServerUDP")
                    .getField("clients").get(null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            LazoDiscs.LOGGER.warn("Could not read Plasmo Voice 1 UDP sessions", error);
            return Map.of();
        }
    }
}
