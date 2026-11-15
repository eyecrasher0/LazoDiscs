package su.plo.voice.client;

import java.util.ArrayList;
import java.util.List;

/** Controlled Voice 1 public API fixture; no native or game implementation. */
public final class VoiceClient {
    public static final Engine ENGINE = new Engine();
    public static final Config CONFIG = new Config();
    public static Engine getSoundEngine() { return ENGINE; }
    public static Config getClientConfig() { return CONFIG; }
    public static final class Setting<T> {
        public T value;
        public Setting(T value) { this.value=value; }
        public T get() { return value; }
    }
    public static final class Config {
        public final Setting<Double> voiceVolume = new Setting<>(1.0);
        public final Setting<Boolean> speakerMuted = new Setting<>(false);
    }
    public static final class Engine {
        public long context = 1;
        public final List<Source> sources = new ArrayList<>();
        private final List<Runnable> pending = new ArrayList<>();
        public void runInContext(Runnable action) { pending.add(action); }
        public long getContextPointer() { return context; }
        public Source createSource() { Source source=new Source();sources.add(source);return source; }
        public void flush() { var work=List.copyOf(pending);pending.clear();work.forEach(Runnable::run); }
    }
    public static final class Source {
        public boolean closed, relative, looping;
        public int writes;
        public float volume, fade, distance;
        public Object position;
        public void setRelative(boolean value) { relative=value; }
        public void setLooping(boolean value) { looping=value; }
        public void setVolume(float value) { volume=value; }
        public void setPosition(Object value) { position=value; }
        public void setFadeDistance(float value) { fade=value; }
        public void setMaxDistance(float value, float factor) { distance=value; }
        public void write(byte[] pcm) { if(closed)throw new AssertionError("Closed source written");writes++; }
        public void close() { closed=true; }
    }
}
