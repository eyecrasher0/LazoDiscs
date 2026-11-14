package com.eyecrasher.lazodiscs.data;

import java.util.Objects;
import java.util.UUID;

public record CustomDiscData(String url, String title, int range, float volume, UUID id) {
    public CustomDiscData {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(id, "id");
        range = Math.max(1, range);
        volume = Math.max(0.0F, volume);
    }
}
