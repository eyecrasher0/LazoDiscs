package org.lwjgl.system;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class MemoryUtil {
    public static final Set<Buffer> LIVE = Collections.newSetFromMap(new IdentityHashMap<>());
    public static int allocations;

    public static ByteBuffer memAlloc(int bytes) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(bytes);
        LIVE.add(buffer);
        allocations++;
        return buffer;
    }

    public static void memFree(Buffer buffer) {
        if (!LIVE.remove(buffer)) throw new AssertionError("Unknown or already released allocation");
    }
}
