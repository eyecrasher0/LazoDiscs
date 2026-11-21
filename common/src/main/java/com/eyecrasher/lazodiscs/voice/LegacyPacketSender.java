package com.eyecrasher.lazodiscs.voice;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** Orders natural EOF after queued Voice 1 frames; manual stops discard pending work. */
public final class LegacyPacketSender implements StreamingSession.Sink {
    private final UUID source;
    private final Consumer<Runnable> dispatch;
    private final BiFunction<byte[], Long, LegacyAudioPacket> frame;
    private final Consumer<LegacyAudioPacket> deliver;
    private final AtomicBoolean ending = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();

    public LegacyPacketSender(UUID source, Consumer<Runnable> dispatch,
            BiFunction<byte[], Long, LegacyAudioPacket> frame, Consumer<LegacyAudioPacket> deliver) {
        this.source = source;
        this.dispatch = dispatch;
        this.frame = frame;
        this.deliver = deliver;
    }

    @Override public void frame(byte[] opus, long sequence) {
        if (ending.get()) return;
        byte[] owned = opus.clone();
        dispatch.accept(() -> {
            if (!stopped.get()) deliver.accept(frame.apply(owned, sequence));
        });
    }

    @Override public void finish() {
        if (!ending.compareAndSet(false, true)) return;
        dispatch.accept(() -> {
            if (!stopped.get()) deliver.accept(LegacyAudioPacket.end(source));
        });
    }

    @Override public void close() {
        ending.set(true);
        if (stopped.compareAndSet(false, true)) dispatch.accept(() -> deliver.accept(LegacyAudioPacket.stop(source)));
    }
}
