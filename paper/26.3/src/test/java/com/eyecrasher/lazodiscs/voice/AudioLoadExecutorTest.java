package com.eyecrasher.lazodiscs.voice;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AudioLoadExecutorTest {
    @Test void cancellingThroughThePublicExecutorKeepsRunningArchiveReadsUninterrupted() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var future = AudioLoadExecutor.submit(() -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException error) { interrupted.set(true); }
            finally { finished.countDown(); }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(future.cancel(true));
            assertTrue(future.isCancelled());
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertFalse(interrupted.get());
        } finally {
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            AudioLoadExecutor.shutdownNow();
        }
    }
}
