package org.lwjgl.openal;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class AL10 {
    public static final Set<Integer> buffers = new HashSet<>();
    private static final Map<Integer, ArrayDeque<Integer>> QUEUES = new HashMap<>();
    private static int nextBuffer;
    public static boolean processed;
    public static int deletions;
    public static int allocations;

    public static void resetContext() {
        buffers.clear();
        QUEUES.clear();
        nextBuffer = 0;
    }

    public static int alGenBuffers() {
        int id = ++nextBuffer;
        buffers.add(id);
        allocations++;
        return id;
    }

    public static void alDeleteBuffers(int buffer) {
        if (QUEUES.values().stream().anyMatch(queue -> queue.contains(buffer)) || !buffers.remove(buffer)) {
            throw new AssertionError("Deleting a queued or invalid buffer");
        }
        deletions++;
    }

    public static int alGetSourcei(int source, int parameter) {
        if (parameter == 0x1015) return queue(source).size();
        if (parameter != 0x1016) throw new AssertionError("Expected processed-buffer query");
        return processed ? queue(source).size() : 0;
    }

    public static void alSourcei(int source, int parameter, int value) {
        if (parameter != 0x1009 || value != 0) throw new AssertionError("Expected queue detachment");
        queue(source).clear();
    }

    public static int alSourceUnqueueBuffers(int source) {
        if (!processed) throw new AssertionError("Unqueue before processing");
        return queue(source).removeFirst();
    }

    public static void alSourceQueueBuffers(int source, int buffer) {
        queue(source).addLast(buffer);
    }

    public static void alBufferData(int buffer, int format, ByteBuffer pcm, int rate) {
        if (!buffers.contains(buffer) || format != 0x1101 || rate != 48000 ||
                pcm.remaining() != 2 || pcm.get(0) != 1 || pcm.get(1) != 2) {
            throw new AssertionError("Expected Voice decoder's mono 16-bit PCM");
        }
    }

    public static void close(int source) {
        var queue = queue(source);
        while (!queue.isEmpty()) alDeleteBuffers(queue.removeFirst());
        QUEUES.remove(source);
    }

    private static ArrayDeque<Integer> queue(int source) {
        return QUEUES.computeIfAbsent(source, ignored -> new ArrayDeque<>());
    }
}
