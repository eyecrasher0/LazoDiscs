package com.eyecrasher.lazodiscs.service;

import java.util.List;
import java.util.Objects;

public record TrackMetadata(String title, List<String> artists, Long durationMs) {
    public TrackMetadata {
        title = title == null ? "" : title.trim();
        artists =
                artists == null
                        ? List.of()
                        : List.copyOf(
                                artists.stream()
                                        .filter(Objects::nonNull)
                                        .map(String::trim)
                                        .filter(value -> !value.isBlank())
                                        .toList());
    }

    public String searchQuery() {
        StringBuilder query = new StringBuilder(title);
        for (String artist : artists) {
            query.append(' ').append(artist);
        }
        return query.toString().replaceAll("\\s+", " ").trim();
    }

    public String primaryArtist() {
        return artists.isEmpty() ? "" : artists.get(0);
    }
}
