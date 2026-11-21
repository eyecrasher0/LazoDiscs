package com.eyecrasher.lazodiscs.voice;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.helpers.MessageFormatter;

public final class AudioLogs {
    private static final Pattern URL = Pattern.compile("(?i)(?:\\b[a-z][a-z0-9+.-]*:|(?<![\\w./:]))//[^\\s<>\"]+");

    private AudioLogs() {}

    public static Logger getLogger(Class<?> owner) { return getLogger(owner.getName()); }

    public static Logger getLogger(String name) {
        Logger delegate = LoggerFactory.getLogger(name);
        // Paper 1.17-1.18 still provides SLF4J 1.7. Keep this boundary on its
        // public Logger/MessageFormatter API, which is also supported by 2.x.
        return (Logger) Proxy.newProxyInstance(Logger.class.getClassLoader(), new Class<?>[]{Logger.class},
                (proxy, method, args) -> {
                    String operation = method.getName();
                    if (operation.equals("equals")) return proxy == args[0];
                    if (operation.equals("hashCode")) return System.identityHashCode(proxy);
                    if (method.getReturnType().getName().endsWith(".spi.LoggingEventBuilder")) {
                        String namespace = Logger.class.getPackage().getName();
                        Class<?> levelType = Class.forName(namespace + ".event.Level");
                        Object level = args != null && args.length == 1 ? args[0]
                                : levelType.getField(operation.substring(2).toUpperCase(java.util.Locale.ROOT)).get(null);
                        return Class.forName(namespace + ".spi.DefaultLoggingEventBuilder")
                                .getConstructor(Logger.class, levelType).newInstance(proxy, level);
                    }
                    if (!operation.equals("trace") && !operation.equals("debug") && !operation.equals("info")
                            && !operation.equals("warn") && !operation.equals("error")) return invoke(delegate, method, args);
                    int offset = method.getParameterTypes()[0] == Marker.class ? 1 : 0;
                    Marker marker = offset == 0 ? null : (Marker) args[0];
                    if (!enabled(delegate, marker, operation)) return null;
                    Object[] values;
                    if (args.length == offset + 2 && method.getParameterTypes()[offset + 1] == Object[].class) {
                        values = (Object[]) args[offset + 1];
                    } else {
                        values = java.util.Arrays.copyOfRange(args, offset + 1, args.length);
                    }
                    org.slf4j.helpers.FormattingTuple formatted = MessageFormatter.arrayFormat((String) args[offset], values);
                    Throwable error = formatted.getThrowable();
                    String message = redact(formatted.getMessage(), error);
                    if (YoutubeLoginLogFilter.replace(name, error, message)) return null;
                    String stack = redactedStack(error);
                    if (stack != null) {
                        message += System.lineSeparator() + stack;
                        error = null;
                    }
                    switch (operation) {
                        case "trace": delegate.trace(marker, message, error); break;
                        case "debug": delegate.debug(marker, message, error); break;
                        case "info": delegate.info(marker, message, error); break;
                        case "warn": delegate.warn(marker, message, error); break;
                        case "error": delegate.error(marker, message, error); break;
                        default: throw new AssertionError(operation);
                    }
                    return null;
                });
    }

    public static org.apache.logging.log4j.Logger getLog4jLogger(String name) {
        org.apache.logging.log4j.spi.ExtendedLogger delegate =
                (org.apache.logging.log4j.spi.ExtendedLogger) org.apache.logging.log4j.LogManager.getLogger(name);
        return new org.apache.logging.log4j.spi.ExtendedLoggerWrapper(delegate, name, delegate.getMessageFactory()) {
            @Override public void logMessage(String caller, org.apache.logging.log4j.Level level,
                    org.apache.logging.log4j.Marker marker, org.apache.logging.log4j.message.Message message,
                    Throwable error) {
                String safe = redact(message == null ? null : message.getFormattedMessage(), error);
                if (YoutubeLoginLogFilter.replace(name, error, safe)) return;
                String stack = redactedStack(error);
                if (stack != null) {
                    safe += System.lineSeparator() + stack;
                    error = null;
                }
                org.apache.logging.log4j.message.Message rendered =
                        new org.apache.logging.log4j.message.SimpleMessage(safe);
                super.logMessage(caller, level, marker, rendered, error);
            }
        };
    }

    public static Log getLog(Class<?> owner) { return getLog(owner.getName()); }

    public static Log getLog(String name) {
        Log delegate = LogFactory.getLog(name);
        // HTTP debug messages can contain relative request targets, cookies and
        // Authorization headers without a complete URL to redact. Keep errors
        // and warnings while disabling that protocol-level output for our client.
        boolean httpDetails = name.startsWith("org.apache.http.") || name.contains(".shadow.org.apache.http.");
        return (Log) Proxy.newProxyInstance(Log.class.getClassLoader(), new Class<?>[]{Log.class},
                (proxy, method, args) -> {
                    String operation = method.getName();
                    if (operation.equals("equals")) return proxy == args[0];
                    if (operation.equals("hashCode")) return System.identityHashCode(proxy);
                    if (httpDetails && ("isDebugEnabled".equals(operation) || "isTraceEnabled".equals(operation))) return false;
                    if (args == null || method.getParameterTypes()[0] != Object.class || operation.equals("equals"))
                        return invoke(delegate, method, args);
                    if (httpDetails && ("debug".equals(operation) || "trace".equals(operation))) return null;
                    Throwable error = args.length == 2 ? (Throwable) args[1] : null;
                    String message = redact(String.valueOf(args[0]), error);
                    String stack = redactedStack(error);
                    args[0] = stack == null ? message : message + System.lineSeparator() + stack;
                    if (stack != null) args[1] = null;
                    return invoke(delegate, method, args);
                });
    }

    private static Object invoke(Object delegate, Method method, Object[] args) throws Throwable {
        try { return method.invoke(delegate, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }

    private static boolean enabled(Logger logger, Marker marker, String operation) {
        switch (operation) {
            case "trace": return logger.isTraceEnabled(marker);
            case "debug": return logger.isDebugEnabled(marker);
            case "info": return logger.isInfoEnabled(marker);
            case "warn": return logger.isWarnEnabled(marker);
            case "error": return logger.isErrorEnabled(marker);
            default: throw new AssertionError(operation);
        }
    }

    static String redact(String text) { return redact(text, null); }

    private static String redact(String text, Throwable error) {
        if (text == null) return null;
        // URI syntax errors echo their complete input, including malformed relative
        // redirects with spaces. Scrub that exact input without mutating the cause.
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.ArrayDeque<Throwable> pending = new java.util.ArrayDeque<>();
        if (error != null) pending.add(error);
        while (!pending.isEmpty()) {
            Throwable cause = pending.removeFirst();
            if (!seen.add(cause)) continue;
            if (cause instanceof java.net.URISyntaxException) {
                String input = ((java.net.URISyntaxException) cause).getInput();
                if (input != null && !input.isEmpty()) text = text.replace(input, "[media URL omitted]");
            }
            if (cause.getCause() != null) pending.add(cause.getCause());
            java.util.Collections.addAll(pending, cause.getSuppressed());
        }
        Matcher urls = URL.matcher(text);
        if (!urls.find()) return text;
        StringBuffer safe = new StringBuffer();
        do {
            String origin = "[media URL omitted]";
            try {
                URI uri = URI.create(urls.group());
                if (uri.getHost() != null) {
                    origin = (uri.getScheme() == null ? "" : uri.getScheme() + ":") + "//" + uri.getHost()
                            + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + "/…";
                }
            } catch (IllegalArgumentException invalidUrl) {
                origin = "[media URL omitted]";
            }
            urls.appendReplacement(safe, Matcher.quoteReplacement(origin));
        } while (urls.find());
        return urls.appendTail(safe).toString();
    }

    private static String redactedStack(Throwable error) {
        if (error == null) return null;
        StringWriter rendered = new StringWriter();
        error.printStackTrace(new PrintWriter(rendered));
        String stack = rendered.toString();
        String safe = redact(stack, error);
        return safe.equals(stack) ? null : safe;
    }

}
