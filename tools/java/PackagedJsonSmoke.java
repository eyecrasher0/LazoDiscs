import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;

/** Executes the private JSON implementations in the final JAR, without a game classpath. */
public final class PackagedJsonSmoke {
    private static final String PREFIX = "com.eyecrasher.lazodiscs.shadow.";

    private static Class<?> bundled(String name, File expected) throws Exception {
        Class<?> type = Class.forName(PREFIX + name);
        File origin = new File(type.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!origin.getCanonicalFile().equals(expected.getCanonicalFile())) {
            throw new AssertionError("JSON implementation came from another archive: " + origin);
        }
        return type;
    }

    private static void check(Map<?, ?> value) {
        if (!"Пластинка 🎵".equals(value.get("title"))
                || ((Number) value.get("range")).intValue() != 64
                || !Boolean.TRUE.equals(((Map<?, ?>) value.get("options")).get("enabled"))) {
            throw new AssertionError("JSON round trip changed track metadata: " + value);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected final JAR path");
        File jar = new File(args[0]);
        String json = "{\"title\":\"Пластинка 🎵\",\"range\":64,\"options\":{\"enabled\":true}}";
        Class<?> gsonType = bundled("com.google.gson.Gson", jar);
        Object gson = gsonType.getConstructor().newInstance();
        Method parse = gsonType.getMethod("fromJson", String.class, Class.class);
        Object decoded = parse.invoke(gson, json, Map.class);
        check((Map<?, ?>) decoded);
        String encoded = (String) gsonType.getMethod("toJson", Object.class).invoke(gson, decoded);
        check((Map<?, ?>) parse.invoke(gson, encoded, Map.class));

        Class<?> mapperType = bundled("com.fasterxml.jackson.databind.ObjectMapper", jar);
        Object mapper = mapperType.getConstructor().newInstance();
        Method read = mapperType.getMethod("readValue", String.class, Class.class);
        Object document = read.invoke(mapper, json, Map.class);
        check((Map<?, ?>) document);
        encoded = (String) mapperType.getMethod("writeValueAsString", Object.class).invoke(mapper, document);
        check((Map<?, ?>) read.invoke(mapper, encoded, Map.class));
        System.out.println("PASS bundled Gson and Jackson JSON round trips: " + jar.getName());
    }
}
