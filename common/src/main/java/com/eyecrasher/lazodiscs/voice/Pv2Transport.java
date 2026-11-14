package com.eyecrasher.lazodiscs.voice;

import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;
import com.eyecrasher.lazodiscs.text.LazoDiscsText;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;

/** Public-API compatibility between Voice 2.0's mc-lib and Voice 2.1's mc-slib. */
public final class Pv2Transport {
    public interface PositionalSink extends StreamingSession.Sink { void move(Object world, double x, double y, double z); }
    private final Object server;
    private final Object line;
    private final ClassLoader loader;
    private final Class<?> positionType;
    private final Constructor<?> packetConstructor;

    public Pv2Transport(Object server, Object addon) {
        this.server = server;
        loader = server.getClass().getClassLoader();
        try (InputStream icon = getClass().getClassLoader().getResourceAsStream("assets/lazodiscs/textures/icons/discs.png")) {
            Object manager = call(server, "getSourceLineManager");
            Object builder = call(manager, "createBuilder", addon, "discs", LazoDiscsText.sourceLineName(), icon, 50);
            call(builder, "setDefaultVolume", LazoDiscsConfig.SOURCE_LINE_DEFAULT_VOLUME.get());
            line = call(builder, "build");
            Class<?> position;
            try { position = Class.forName("su.plo.slib.api.server.position.ServerPos3d", true, loader); }
            catch (ClassNotFoundException oldApi) { position = Class.forName("su.plo.lib.api.server.world.ServerPos3d", true, loader); }
            positionType = position;
            packetConstructor = Class.forName("su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket", true, loader)
                    .getConstructor(long.class, byte.class, byte[].class, java.util.UUID.class, short.class);
        } catch (Exception error) { throw new IllegalStateException("Cannot initialize Plasmo Voice 2 integration", error); }
    }

    public PositionalSink source(Object world, double x, double y, double z, String title, int range) {
        Object minecraft = call(server, "getMinecraftServer");
        Object wrappedWorld = call(minecraft, "getWorld", world);
        if (wrappedWorld == null) throw new IllegalStateException("Plasmo Voice does not know this world");
        Object position = construct(positionType, wrappedWorld, x, y, z);
        return sink(createSource("createStaticSource", position), title, range, true);
    }

    public StreamingSession.Sink entitySource(Object entity, String title, int range) {
        Object minecraft = call(server, "getMinecraftServer");
        Object wrapped;
        try { wrapped = call(minecraft, "getEntityByInstance", entity); }
        catch (MissingMethod oldApi) { wrapped = call(minecraft, "getEntity", entity); }
        if (wrapped == null) throw new IllegalStateException("Plasmo Voice does not know this entity");
        return sink(createSource("createEntitySource", wrapped), title, range, false);
    }

    private Object createSource(String method, Object target) {
        try { return call(line, method, target, false); }
        catch (MissingMethod oldApi) {
            try {
                Object codec = Class.forName("su.plo.voice.proto.data.audio.codec.CodecInfo", true, loader)
                        .getConstructor(String.class, java.util.Map.class).newInstance("opus", Collections.emptyMap());
                return call(line, method, target, false, codec);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot create Voice 2.0 source", error); }
        }
    }

    private PositionalSink sink(final Object audio, String title, int range, boolean movable) {
        call(audio, "setName", title);
        Object encryption = call(server, "getDefaultEncryption");
        return new PositionalSink() {
            private volatile boolean removed;
            @Override public void frame(byte[] opus, long sequence) throws Exception {
                if (removed) return;
                byte[] encrypted = (byte[]) call(encryption, "encrypt", opus);
                Object packet = packetConstructor.newInstance(sequence, ((Number) call(audio, "getState")).byteValue(), encrypted, call(audio, "getId"), (short) range);
                call(audio, "sendAudioPacket", packet, (short) range);
            }
            @Override public void move(Object world, double x, double y, double z) {
                if (removed || !movable) return;
                Object wrapped = call(call(server, "getMinecraftServer"), "getWorld", world);
                if (wrapped == null) throw new IllegalStateException("Plasmo Voice does not know this world");
                call(audio, "setPosition", construct(positionType, wrapped, x, y, z));
            }
            @Override public void close() { if (!removed) { removed = true; call(audio, "remove"); } }
        };
    }

    public Object line() { return line; }

    private static Object construct(Class<?> type, Object... values) {
        for (Constructor<?> constructor : type.getConstructors()) {
            if (constructor.getParameterCount() != values.length) continue;
            try { return constructor.newInstance(values); }
            catch (IllegalArgumentException incompatible) { continue; }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        throw new MissingMethod(type.getName());
    }

    public static Object call(Object target, String name, Object... values) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != values.length) continue;
            Object[] converted = values.clone();
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                if (values[i] instanceof Number) {
                    Number n = (Number) values[i];
                    if (types[i] == float.class) converted[i] = n.floatValue();
                    if (types[i] == double.class) converted[i] = n.doubleValue();
                }
            }
            try { return method.invoke(target, converted); }
            catch (IllegalArgumentException incompatible) { continue; }
            catch (ReflectiveOperationException error) {
                Throwable cause = error instanceof InvocationTargetException ? error.getCause() : error;
                throw new IllegalStateException("Plasmo Voice API failed: " + name, cause);
            }
        }
        throw new MissingMethod(target.getClass().getName() + "." + name);
    }
    private static final class MissingMethod extends IllegalStateException {
        MissingMethod(String method) { super("Missing Plasmo Voice API method: " + method); }
    }
}
