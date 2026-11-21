package com.eyecrasher.lazodiscs.voice;

import com.eyecrasher.lazodiscs.LazoDiscs;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import java.net.URI;
import java.util.UUID;

public final class PlaybackProbe {
    static final boolean ENABLED = Boolean.getBoolean("lazodiscs.testPlayback");

    private PlaybackProbe() {}

    static void completed(AudioTrack track) {
        String identifier = track.getIdentifier();
        if (!identifier.startsWith("http://127.0.0.1:")) return;
        String path = URI.create(identifier).getRawPath();
        LazoDiscs.LOGGER.info("LazoDiscs smoke completed {} at {} ms", path, track.getPosition());
    }

    public static void loaderReturned(UUID disc) {
        if (ENABLED) LazoDiscs.LOGGER.info("LazoDiscs smoke loader returned {}", disc);
    }

    public static void emptySlot(UUID disc) {
        if (ENABLED) LazoDiscs.LOGGER.info("LazoDiscs smoke retired {} from empty jukebox", disc);
    }

    static void sourceRegistered(UUID disc) {
        if (ENABLED) LazoDiscs.LOGGER.info("LazoDiscs smoke registered source {}", disc);
    }

    static void frameProvided(UUID disc) {
        if (ENABLED) LazoDiscs.LOGGER.info("LazoDiscs smoke provided frame {}", disc);
    }
}
