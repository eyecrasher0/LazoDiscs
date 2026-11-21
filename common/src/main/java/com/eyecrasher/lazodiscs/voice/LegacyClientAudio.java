package com.eyecrasher.lazodiscs.voice;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Voice 1's own Opus decoder, OpenAL context, attenuation and user volume settings. */
public final class LegacyClientAudio {
    private static final Map<UUID, Channel> CHANNELS = new HashMap<>();
    private static final java.util.concurrent.atomic.AtomicBoolean reported = new java.util.concurrent.atomic.AtomicBoolean();
    private static void failure(Throwable error) {
        if (reported.compareAndSet(false, true)) java.util.logging.Logger.getLogger("LazoDiscs").log(
                java.util.logging.Level.WARNING, "Could not play LazoDiscs audio through Plasmo Voice 1", error);
    }
    private LegacyClientAudio() {}

    public static void accept(LegacyAudioPacket packet, String world, Function<LegacyAudioPacket, Object> vector) {
        if (packet.kind == LegacyAudioPacket.HELLO) return;
        try {
            Class<?> voice = Class.forName("su.plo.voice.client.VoiceClient");
            Object engine = voice.getMethod("getSoundEngine").invoke(null);
            ReflectiveApi.call(engine, "runInContext", (Runnable) () -> {
                synchronized (CHANNELS) {
                    try {
                        long context = ((Number) ReflectiveApi.call(engine, "getContextPointer")).longValue();
                        Object capabilities = capabilities(context);
                        discardInvalidContexts(engine, context, capabilities);
                        if (context == 0) return;
                        Channel channel = CHANNELS.get(packet.source);
                        if (packet.kind == LegacyAudioPacket.END) {
                            if (channel != null) channel.draining = true;
                            return;
                        }
                        if (packet.kind == LegacyAudioPacket.STOP || !world.equals(packet.world)) {
                            if (channel != null) {
                                CHANNELS.remove(packet.source);
                                channel.close();
                            }
                            return;
                        }
                        if (channel == null) {
                            if (CHANNELS.size() >= 32) return;
                            Class<?> recorder = Class.forName("su.plo.voice.client.sound.Recorder");
                            int rate = ((Number) recorder.getMethod("getSampleRate").invoke(null)).intValue();
                            Constructor<?> decoderFactory = Class.forName("su.plo.voice.client.sound.opus.OpusDecoder")
                                    .getConstructor(int.class, int.class, int.class);
                            Object source = ReflectiveApi.call(engine, "createSource");
                            if (source == null) return;
                            try {
                                Object decoder = decoderFactory.newInstance(rate, rate / 50 * 2, 4096);
                                channel = new Channel(source, decoder, engine, context, capabilities, rate);
                            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                                ReflectiveApi.call(source, "close");
                                throw error;
                            }
                            CHANNELS.put(packet.source, channel);
                            ReflectiveApi.call(source, "setRelative", false);
                            ReflectiveApi.call(source, "setLooping", false);
                        }
                        if (channel.draining || packet.sequence <= channel.sequence) return;
                        channel.sequence = packet.sequence;
                        channel.lastFrame = System.nanoTime();
                        Object config = voice.getMethod("getClientConfig").invoke(null);
                        Object volumeSetting = config.getClass().getField("voiceVolume").get(config);
                        Object mutedSetting = config.getClass().getField("speakerMuted").get(config);
                        float volume = Boolean.TRUE.equals(ReflectiveApi.call(mutedSetting, "get")) ? 0 : ((Number) ReflectiveApi.call(volumeSetting, "get")).floatValue();
                        ReflectiveApi.call(channel.source, "setVolume", volume);
                        ReflectiveApi.call(channel.source, "setPosition", vector.apply(packet));
                        ReflectiveApi.call(channel.source, "setFadeDistance", packet.range / 2.0F);
                        ReflectiveApi.call(channel.source, "setMaxDistance", (float) packet.range, 0.95F);
                        channel.write((byte[]) ReflectiveApi.call(channel.decoder, "decode", packet.opus));
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                        failure(error);
                        Channel old = CHANNELS.remove(packet.source);
                        if (old != null) old.close();
                    }
                }
            });
        } catch (ReflectiveOperationException | RuntimeException unavailable) { failure(unavailable); }
    }

    /** Called on client ticks, including after disconnect and dimension changes. */
    public static void prune(boolean connected) {
        if (!connected) reported.set(false);
        try {
            Object engine = Class.forName("su.plo.voice.client.VoiceClient").getMethod("getSoundEngine").invoke(null);
            ReflectiveApi.call(engine, "runInContext", (Runnable) () -> {
                synchronized (CHANNELS) {
                    long context = ((Number) ReflectiveApi.call(engine, "getContextPointer")).longValue();
                    Object capabilities;
                    try { capabilities = capabilities(context); }
                    catch (ReflectiveOperationException error) { failure(error); return; }
                    discardInvalidContexts(engine, context, capabilities);
                    CHANNELS.entrySet().removeIf(entry -> {
                        Channel channel = entry.getValue();
                        if (connected && System.nanoTime() - channel.lastFrame < 2_000_000_000L
                                && (!channel.draining || !channel.drained())) return false;
                        entry.getValue().close();
                        return true;
                    });
                }
            });
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            synchronized (CHANNELS) {
                for (Channel channel : CHANNELS.values()) channel.freeNativeMemory();
                CHANNELS.clear();
            }
        }
    }
    private static Object capabilities(long context) throws ReflectiveOperationException {
        // LWJGL replaces this identity on context creation even if OpenAL reuses
        // the same native pointer address for the new context.
        return context == 0 ? null : Class.forName("org.lwjgl.openal.AL").getMethod("getCapabilities").invoke(null);
    }
    private static void discardInvalidContexts(Object engine, long context, Object capabilities) {
        CHANNELS.entrySet().removeIf(entry -> {
            Channel channel = entry.getValue();
            if (channel.engine == engine && channel.context == context && channel.capabilities == capabilities) return false;
            // Voice destroyed the old OpenAL context. Its numeric source IDs can now
            // belong to unrelated sounds.
            channel.freeNativeMemory();
            return true;
        });
    }
    private static final class Channel {
        final Object source, decoder, engine, capabilities;
        final long context;
        final int rate;
        final int[] buffers = new int[8];
        ByteBuffer pcm;
        int allocated;
        boolean draining;
        long sequence = -1, lastFrame = System.nanoTime();
        Channel(Object source, Object decoder, Object engine, long context, Object capabilities, int rate) {
            this.source = source; this.decoder = decoder; this.engine = engine; this.context = context; this.capabilities = capabilities;
            this.rate = rate;
        }

        void write(byte[] frame) throws ReflectiveOperationException {
            int capacity = rate / 50 * 2;
            if (frame.length == 0 || frame.length > capacity) return;
            int pointer = ((Number) ReflectiveApi.call(source, "getPointer")).intValue();
            int id;
            int processed = (Integer) OpenAl.GET_SOURCE_INT.invoke(null, pointer, OpenAl.BUFFERS_PROCESSED);
            if (processed > 0) {
                id = (Integer) OpenAl.UNQUEUE.invoke(null, pointer);
            } else if (allocated < buffers.length) {
                id = (Integer) OpenAl.GEN_BUFFER.invoke(null);
                if (id == 0) throw new IllegalStateException("Could not allocate an OpenAL buffer");
                buffers[allocated++] = id;
            } else {
                return;
            }
            if (pcm == null) pcm = (ByteBuffer) OpenAl.ALLOCATE.invoke(null, capacity);
            ((Buffer) pcm).clear();
            pcm.put(frame);
            ((Buffer) pcm).flip();
            OpenAl.UPLOAD.invoke(null, id, OpenAl.MONO_16, pcm, rate);
            OpenAl.QUEUE.invoke(null, pointer, id);
            ReflectiveApi.call(source, "play");
        }

        void freeNativeMemory() {
            try {
                if (pcm != null) OpenAl.FREE.invoke(null, pcm);
            } catch (ReflectiveOperationException | RuntimeException error) {
                failure(error);
            } finally {
                pcm = null;
                try { ReflectiveApi.call(decoder, "close"); }
                catch (RuntimeException error) { failure(error); }
            }
        }

        boolean drained() {
            try {
                int pointer = ((Number) ReflectiveApi.call(source, "getPointer")).intValue();
                int queued = (Integer) OpenAl.GET_SOURCE_INT.invoke(null, pointer, OpenAl.BUFFERS_QUEUED);
                int processed = (Integer) OpenAl.GET_SOURCE_INT.invoke(null, pointer, OpenAl.BUFFERS_PROCESSED);
                return processed >= queued;
            } catch (ReflectiveOperationException | RuntimeException error) {
                failure(error);
                return true;
            }
        }

        void close() {
            try {
                if (allocated > 0) {
                    int pointer = ((Number) ReflectiveApi.call(source, "getPointer")).intValue();
                    ReflectiveApi.call(source, "stop");
                    OpenAl.SET_SOURCE_INT.invoke(null, pointer, OpenAl.BUFFER, 0);
                }
                ReflectiveApi.call(source, "close");
                for (int i = 0; i < allocated; i++) OpenAl.DELETE_BUFFER.invoke(null, buffers[i]);
            } catch (ReflectiveOperationException | RuntimeException error) {
                failure(error);
            } finally {
                freeNativeMemory();
            }
        }
    }

    private static final class OpenAl {
        static final int MONO_16 = 0x1101;
        static final int BUFFER = 0x1009;
        static final int BUFFERS_PROCESSED = 0x1016;
        static final int BUFFERS_QUEUED = 0x1015;
        private static final String AL10 = "org.lwjgl.openal.AL10";
        private static final String MEMORY = "org.lwjgl.system.MemoryUtil";
        static final Method GET_SOURCE_INT = method(AL10, "alGetSourcei", int.class, int.class);
        static final Method SET_SOURCE_INT = method(AL10, "alSourcei", int.class, int.class, int.class);
        static final Method GEN_BUFFER = method(AL10, "alGenBuffers");
        static final Method DELETE_BUFFER = method(AL10, "alDeleteBuffers", int.class);
        static final Method UNQUEUE = method(AL10, "alSourceUnqueueBuffers", int.class);
        static final Method QUEUE = method(AL10, "alSourceQueueBuffers", int.class, int.class);
        static final Method UPLOAD = method(AL10, "alBufferData", int.class, int.class, ByteBuffer.class, int.class);
        static final Method ALLOCATE = method(MEMORY, "memAlloc", int.class);
        static final Method FREE = method(MEMORY, "memFree", Buffer.class);

        private static Method method(String owner, String name, Class<?>... types) {
            try { return Class.forName(owner).getMethod(name, types); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(name, error); }
        }
    }
}
