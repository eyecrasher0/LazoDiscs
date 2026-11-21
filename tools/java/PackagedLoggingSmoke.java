import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/** Executes the loggers created by the shipped LavaPlayer and HTTP classes. */
public final class PackagedLoggingSmoke {
    private static final String URL_WITH_SECRET = "https://o'brien:password-secret@media.example/path-secret/file.wav?token=query-secret";
    private static final List<LogRecord> EVENTS = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        URL[] locations = new URL[args.length];
        for (int i = 0; i < args.length; i++) locations[i] = new File(args[i]).toURI().toURL();
        try (URLClassLoader loader = new URLClassLoader(locations, ClassLoader.getSystemClassLoader().getParent())) {
            Thread.currentThread().setContextClassLoader(loader);
            String prefix = loader.findResource("com/eyecrasher/lazodiscs/shadow/org/slf4j/Logger.class") == null
                    ? "" : "com.eyecrasher.lazodiscs.shadow.";
            Class<?> loggerType = loader.loadClass(prefix + "org.slf4j.Logger");
            Method warn = loggerType.getMethod("warn", String.class, Throwable.class);
            String owner = "com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager";
            Logger host = Logger.getLogger(owner);
            host.setUseParentHandlers(false);
            host.setLevel(Level.ALL);
            Handler capture = new Handler() {
                public void publish(LogRecord record) { EVENTS.add(record); }
                public void flush() {}
                public void close() {}
            };
            capture.setLevel(Level.ALL);
            host.addHandler(capture);

            // This field is initialized by the actual post-Shadow logging-factory call.
            Field logField = loader.loadClass(owner).getDeclaredField("log");
            logField.setAccessible(true);
            Object bundledLogger = logField.get(null);
            IOException original = new IOException("transport detail " + URL_WITH_SECRET,
                    new IllegalStateException("original decoder cause"));
            warn.invoke(bundledLogger, "Loading " + URL_WITH_SECRET, original);
            require(EVENTS.size() == 1, "Bundled LavaPlayer logger did not emit its warning");
            redacted(EVENTS.remove(0), Level.WARNING);
            try {
                Method atWarn = loggerType.getMethod("atWarn");
                Class<?> builderType = loader.loadClass(prefix + "org.slf4j.spi.LoggingEventBuilder");
                Object builder = atWarn.invoke(bundledLogger);
                builderType.getMethod("setCause", Throwable.class).invoke(builder, original);
                builderType.getMethod("log", String.class, Object.class).invoke(builder, "Loading {}", URL_WITH_SECRET);
                require(EVENTS.size() == 1, "SLF4J 2 fluent warning did not reach the owned boundary");
                redacted(EVENTS.remove(0), Level.WARNING);
            } catch (NoSuchMethodException legacySlf4j) {
                // SLF4J 1.7 hosts expose only the classic logging API exercised above.
            }
            require(original.getMessage().equals("transport detail " + URL_WITH_SECRET), "Original caller exception was modified");
            String relative = "/path-secret/file.wav?token=query-secret bad";
            java.net.URISyntaxException syntax = new java.net.URISyntaxException(relative, "Invalid redirect", 5);
            IllegalArgumentException malformed = new IllegalArgumentException("Invalid redirect: " + relative, syntax);
            warn.invoke(bundledLogger, "Could not follow " + relative, malformed);
            require(EVENTS.size() == 1, "Malformed redirect warning was lost");
            String diagnostic = EVENTS.remove(0).getMessage();
            require(!diagnostic.contains("path-secret") && !diagnostic.contains("query-secret"), "Malformed relative URL leaked");
            require(diagnostic.contains("java.net.URISyntaxException: Invalid redirect at index 5"), "URI diagnostic details were lost");
            require(syntax.getInput().equals(relative), "Original syntax exception was mutated");
            IOException unrelated = new IOException("Unrelated decoder error");
            unrelated.setStackTrace(new StackTraceElement[]{
                    new StackTraceElement("LazoDiscs.jar//example.Audio", "read", "Audio.java", 42)});
            warn.invoke(bundledLogger, "No URL here", unrelated);
            require(EVENTS.remove(0).getThrown() == unrelated, "Unrelated Throwable lost its identity");

            List<String> warnings = new ArrayList<>();
            Class<?> filterType = loader.loadClass("com.eyecrasher.lazodiscs.voice.YoutubeLoginLogFilter");
            Object filter = filterType.getConstructor(Consumer.class).newInstance((Consumer<String>) warnings::add);
            filterType.getMethod("install").invoke(filter);
            Throwable refusal = refusal(loader);
            try {
                warn.invoke(bundledLogger, "Suspicious exception for playback of fixture", refusal);
                require(warnings.size() == 1 && EVENTS.isEmpty(), "Known refusal was not replaced by a short warning");
                Object hostLogger = loader.loadClass(prefix + "org.slf4j.LoggerFactory").getMethod("getLogger", String.class).invoke(null, owner);
                warn.invoke(hostLogger, "Other plugin's logger", refusal);
                require(EVENTS.remove(0).getThrown() == refusal, "Another plugin's logger was filtered");
            } finally { ((AutoCloseable) filter).close(); }
            warn.invoke(bundledLogger, "After disable", refusal);
            require(EVENTS.remove(0).getThrown() == refusal, "Disable did not release the refusal filter");

            String httpPrefix = "com.eyecrasher.lazodiscs.shadow.org.apache.";
            String backend = httpPrefix + "commons.logging.impl.Jdk14Logger";
            System.setProperty(httpPrefix + "commons.logging.Log", backend);
            System.setProperty("org.apache.commons.logging.Log", backend);
            Class<?> poolType = loader.loadClass(httpPrefix + "http.impl.conn.PoolingHttpClientConnectionManager");
            Object pool = poolType.getConstructor().newInstance();
            try {
                Logger httpHost = Logger.getLogger(poolType.getName());
                httpHost.setUseParentHandlers(false);
                httpHost.setLevel(Level.ALL);
                httpHost.addHandler(capture);
                Field httpField = poolType.getDeclaredField("log");
                httpField.setAccessible(true);
                Object httpLogger = httpField.get(pool);
                Class<?> logType = loader.loadClass(httpPrefix + "commons.logging.Log");
                logType.getMethod("warn", Object.class, Throwable.class).invoke(httpLogger, "HTTP " + URL_WITH_SECRET, original);
                require(EVENTS.size() == 1, "Bundled HTTP logger did not emit its warning");
                redacted(EVENTS.remove(0), Level.WARNING);

                Object wire = loader.loadClass("com.eyecrasher.lazodiscs.voice.AudioLogs")
                        .getMethod("getLog", String.class).invoke(null, httpPrefix + "http.wire");
                require(Boolean.FALSE.equals(logType.getMethod("isDebugEnabled").invoke(wire)), "HTTP wire logging remained enabled");
                logType.getMethod("debug", Object.class).invoke(wire, "Authorization: Bearer wire-secret");
                require(EVENTS.isEmpty(), "Raw HTTP credentials reached a log handler");
            } finally { poolType.getMethod("shutdown").invoke(pool); }
            realHttpRequest(loader, httpPrefix, capture);
            System.out.println("PASS shipped LavaPlayer/HTTP log redaction, preserved exceptions, scoped YouTube warning and disable cleanup");
        }
    }

    private static void realHttpRequest(ClassLoader loader, String httpPrefix, Handler capture) throws Exception {
        Logger httpHost = Logger.getLogger(httpPrefix + "http");
        httpHost.setUseParentHandlers(false);
        httpHost.setLevel(Level.ALL);
        httpHost.addHandler(capture);
        try (java.net.ServerSocket server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            java.util.concurrent.CompletableFuture<String> received = new java.util.concurrent.CompletableFuture<>();
            Thread responder = new Thread(() -> {
                try (java.net.Socket peer = server.accept()) {
                    peer.setSoTimeout(5000);
                    java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(peer.getInputStream(), "US-ASCII"));
                    String target = input.readLine();
                    String header;
                    while ((header = input.readLine()) != null && !header.isEmpty()) { /* Consume real request headers. */ }
                    peer.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".getBytes("US-ASCII"));
                    peer.getOutputStream().flush();
                    received.complete(target);
                } catch (Throwable error) { received.completeExceptionally(error); }
            }, "Logging HTTP control");
            responder.setDaemon(true);
            responder.start();
            Object client = loader.loadClass(httpPrefix + "http.impl.client.HttpClients").getMethod("createDefault").invoke(null);
            try {
                Object request = loader.loadClass(httpPrefix + "http.client.methods.HttpGet").getConstructor(String.class)
                        .newInstance("http://127.0.0.1:" + server.getLocalPort() + "/path-secret/file.wav?token=query-secret");
                Class<?> requestType = loader.loadClass(httpPrefix + "http.client.methods.HttpUriRequest");
                requestType.getMethod("addHeader", String.class, String.class).invoke(request, "Authorization", "Bearer header-secret");
                Object response = loader.loadClass(httpPrefix + "http.impl.client.CloseableHttpClient")
                        .getMethod("execute", requestType).invoke(client, request);
                ((java.io.Closeable) response).close();
                require(received.get(5, java.util.concurrent.TimeUnit.SECONDS).contains("token=query-secret"), "Signed request did not reach the media server");
                for (LogRecord event : EVENTS) {
                    String rendered = event.getMessage();
                    require(!rendered.contains("path-secret") && !rendered.contains("query-secret") && !rendered.contains("header-secret"),
                            "Bundled HTTP protocol logging leaked a request target or credential: " + rendered);
                }
                EVENTS.clear();
            } finally { ((java.io.Closeable) client).close(); }
            responder.join(5000);
            require(!responder.isAlive(), "HTTP control worker remained active");
        }
    }

    private static Throwable refusal(ClassLoader loader) throws Exception {
        Class<?> severity = loader.loadClass("com.sedmelluq.discord.lavaplayer.tools.FriendlyException$Severity");
        Object common = severity.getField("COMMON").get(null);
        Throwable reason = (Throwable) loader.loadClass("com.sedmelluq.discord.lavaplayer.tools.FriendlyException")
                .getConstructor(String.class, severity, Throwable.class).newInstance("This video requires login.", common, null);
        Object client = loader.loadClass("dev.lavalink.youtube.clients.MWebWithThumbnail").getConstructor().newInstance();
        Object failure = null;
        for (java.lang.reflect.Constructor<?> constructor : loader.loadClass("dev.lavalink.youtube.ClientException").getConstructors()) {
            if (constructor.getParameterTypes().length == 3 && constructor.getParameterTypes()[2] == Throwable.class) {
                failure = constructor.newInstance(reason.getMessage(), client, reason);
                break;
            }
        }
        require(failure != null, "Missing real YouTube client failure constructor");
        return (Throwable) loader.loadClass("dev.lavalink.youtube.AllClientsFailedException")
                .getConstructor(List.class).newInstance(Collections.singletonList(failure));
    }

    private static void redacted(LogRecord event, Level expectedLevel) {
        String text = event.getMessage();
        require(!text.contains("password-secret") && !text.contains("path-secret") && !text.contains("query-secret"), "Media credentials leaked");
        require(text.contains("https://media.example/…"), "Safe media origin is missing");
        require(text.contains("java.io.IOException: transport detail"), "Exception details were lost");
        require(text.contains("Caused by: java.lang.IllegalStateException: original decoder cause"), "Exception cause was lost");
        require(text.contains("PackagedLoggingSmoke.java:"), "Exception stack was lost");
        require(event.getThrown() == null, "Secret-bearing original stack would be rendered twice");
        require(event.getLevel().equals(expectedLevel), "Logging level changed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
