package com.eyecrasher.lazodiscs.data;

/** Vanilla comparator outputs for Paper versions predating the song getter. */
public final class VanillaDiscs {
    private VanillaDiscs() {}

    public static int comparator(String song) {
        return switch (song) {
            case "13" -> 1;
            case "cat" -> 2;
            case "blocks" -> 3;
            case "chirp" -> 4;
            case "far" -> 5;
            case "mall" -> 6;
            case "mellohi" -> 7;
            case "stal" -> 8;
            case "strad", "lava_chicken" -> 9;
            case "ward", "tears" -> 10;
            case "11", "creator_music_box" -> 11;
            case "wait", "creator" -> 12;
            case "pigstep", "precipice" -> 13;
            case "otherside", "relic" -> 14;
            case "5" -> 15;
            default -> throw new IllegalArgumentException("Unknown vanilla jukebox song: " + song);
        };
    }
}
