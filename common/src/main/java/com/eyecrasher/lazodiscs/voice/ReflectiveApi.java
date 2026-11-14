package com.eyecrasher.lazodiscs.voice;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
/** Invokes optional public APIs without linking absent Voice generations on startup. */
public final class ReflectiveApi {
    private ReflectiveApi() {}
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
        throw new IllegalStateException(target.getClass().getName() + "." + name);
    }
}
