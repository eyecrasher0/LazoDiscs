package com.eyecrasher.lazodiscs.voice;

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import dev.lavalink.youtube.AllClientsFailedException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

/** Recognizes only a completed YouTube fallback whose known refusals require sign-in. */
public final class YoutubeLoginFailure {
    public static final String PLAYER_MESSAGE = "YouTube requires sign-in. Please try another link.";
    private YoutubeLoginFailure() {}

    public static boolean matches(Throwable error) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        while (error != null && seen.add(error)) {
            if (error instanceof AllClientsFailedException) {
                AllClientsFailedException failed = (AllClientsFailedException) error;
                boolean loginRequired = false;
                if (failed.getClientExceptions().isEmpty()) return false;
                for (dev.lavalink.youtube.ClientException client : failed.getClientExceptions()) {
                    Throwable cause = client.getCause();
                    if (!(cause instanceof FriendlyException) || cause.getCause() != null || cause.getMessage() == null) return false;
                    String reason = cause.getMessage().trim().toLowerCase(Locale.ROOT);
                    if (reason.endsWith(".")) reason = reason.substring(0, reason.length() - 1);
                    if (reason.equals("this video requires login") || reason.equals("please sign in")) loginRequired = true;
                    else if (!reason.equals("this video is unavailable")) return false;
                }
                return loginRequired;
            }
            error = error.getCause();
        }
        return false;
    }

    public static String shortWarning(String context) {
        String clean = context == null ? "audio load" : context;
        clean = clean.replaceFirst("^Suspicious exception for ", "").replaceAll("[\\r\\n\\p{Cntrl}]", " ");
        if (clean.length() > 180) clean = clean.substring(0, 180);
        return "YouTube requires sign-in (" + clean + "). Try another link.";
    }
}
