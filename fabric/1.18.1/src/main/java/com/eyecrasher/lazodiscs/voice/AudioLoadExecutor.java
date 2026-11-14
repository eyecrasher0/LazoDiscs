package com.eyecrasher.lazodiscs.voice;

import com.eyecrasher.lazodiscs.config.LazoDiscsConfig;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class AudioLoadExecutor {
    private static final Object LOCK = new Object();
    private static ThreadPoolExecutor executor;
    private static int configuredThreads;

    private AudioLoadExecutor() {}

    public static Future<?> submit(Runnable task) {
        synchronized (LOCK) {
            return executor().submit(task);
        }
    }

    public static Future<?> submit(Runnable task, Runnable onRejected) {
        try {
            return submit(task);
        } catch (RejectedExecutionException rejection) {
            onRejected.run();
            return CompletableFuture.failedFuture(rejection);
        }
    }

    public static void shutdownNow() {
        synchronized (LOCK) {
            if (executor != null) {
                cancelPending(executor);
                executor = null;
                configuredThreads = 0;
            }
        }
    }

    private static ExecutorService executor() {
        int threads = Math.max(1, Math.min(32, LazoDiscsConfig.MAX_CONCURRENT_AUDIO_LOADS.get()));
        synchronized (LOCK) {
            if (executor == null) {
                configuredThreads = threads;
                // Bound pending network work without running it on the server thread.
                executor =
                        new ThreadPoolExecutor(
                                threads,
                                threads,
                                0L,
                                TimeUnit.MILLISECONDS,
                                new ArrayBlockingQueue<>(threads * 16),
                                new LoaderThreadFactory(),
                                new ThreadPoolExecutor.AbortPolicy());
            } else if (configuredThreads != threads) {
                // Resize in place so accepted work is preserved and shutdown still owns all
                // workers.
                if (threads > configuredThreads) {
                    executor.setMaximumPoolSize(threads);
                    executor.setCorePoolSize(threads);
                } else {
                    executor.setCorePoolSize(threads);
                    executor.setMaximumPoolSize(threads);
                }
                configuredThreads = threads;
            }
            return executor;
        }
    }

    private static void cancelPending(ExecutorService pool) {
        for (Runnable pending : pool.shutdownNow()) {
            if (pending instanceof Future<?> future) future.cancel(false);
        }
    }

    private static final class LoaderThreadFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread =
                    new Thread(runnable, "LazoDiscs-Audio-Loader-" + nextId.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
