package com.eyecrasher.lazodiscs.voice;

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
                        if (packet.kind == LegacyAudioPacket.STOP || !world.equals(packet.world)) {
                            if (channel != null) { channel.close(); CHANNELS.remove(packet.source); }
                            return;
                        }
                        if (channel == null) {
                            if (CHANNELS.size() >= 32) return;
                            Object source = ReflectiveApi.call(engine, "createSource");
                            if (source == null) return;
                            Class<?> recorder = Class.forName("su.plo.voice.client.sound.Recorder");
                            int rate = ((Number) recorder.getMethod("getSampleRate").invoke(null)).intValue();
                            Object decoder = Class.forName("su.plo.voice.client.sound.opus.OpusDecoder").getConstructor(int.class, int.class, int.class).newInstance(rate, rate / 50 * 2, 4096);
                            channel = new Channel(source, decoder, engine, context, capabilities);
                            ReflectiveApi.call(source, "setRelative", false);
                            ReflectiveApi.call(source, "setLooping", false);
                            CHANNELS.put(packet.source, channel);
                        }
                        if (packet.sequence <= channel.sequence) return;
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
                        ReflectiveApi.call(channel.source, "write", ReflectiveApi.call(channel.decoder, "decode", packet.opus));
                    } catch (ReflectiveOperationException | RuntimeException error) {
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
                        if (connected && System.nanoTime() - entry.getValue().lastFrame < 2_000_000_000L) return false;
                        entry.getValue().close(); return true;
                    });
                }
            });
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            synchronized (CHANNELS) {
                for (Channel channel : CHANNELS.values()) channel.closeDecoder();
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
            // belong to unrelated sounds; only the independent decoder is ours to close.
            channel.closeDecoder();
            return true;
        });
    }
    private static final class Channel {
        final Object source, decoder, engine, capabilities;
        final long context;
        long sequence = -1, lastFrame = System.nanoTime();
        Channel(Object source, Object decoder, Object engine, long context, Object capabilities) {
            this.source = source; this.decoder = decoder; this.engine = engine; this.context = context; this.capabilities = capabilities;
        }
        void closeDecoder() {
            try { ReflectiveApi.call(decoder, "close"); }
            catch (RuntimeException error) { failure(error); }
        }
        void close() { try { ReflectiveApi.call(source, "close"); } finally { closeDecoder(); } }
    }
}
