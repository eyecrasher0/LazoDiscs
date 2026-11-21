package com.eyecrasher.lazodiscs;
import com.eyecrasher.lazodiscs.voice.AudioLoadExecutor;
import com.eyecrasher.lazodiscs.voice.LegacyNetwork;
import com.eyecrasher.lazodiscs.voice.StreamingSession;
import com.eyecrasher.lazodiscs.voice.YoutubeLoginLogFilter;
public final class LazoDiscsServerBootstrap {
    private static boolean loaded;
    private static final YoutubeLoginLogFilter LOGS = new YoutubeLoginLogFilter(message -> LazoDiscs.LOGGER.warn("{}", message));
    private LazoDiscsServerBootstrap() {}
    public static synchronized void loadPlasmoAddon() { LegacyNetwork.initialize(); LOGS.install(); StreamingSession.enable(); loaded = true; }
    public static boolean isLoaded() { return loaded; }
    public static synchronized void resetForIntegratedServer() { loaded = false; StreamingSession.shutdown(); AudioLoadExecutor.shutdownNow(); LOGS.close(); LegacyNetwork.reset(); }
}
