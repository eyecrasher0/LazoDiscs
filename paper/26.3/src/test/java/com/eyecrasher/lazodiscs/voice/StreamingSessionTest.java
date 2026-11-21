package com.eyecrasher.lazodiscs.voice;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StreamingSessionTest {
    @Test void shutdownRetiresTheSenderPoolWithoutInterruptingColdNioReadsAndCanReenable() throws Exception {
        StreamingSession.enable();
        var sinkClosed = new AtomicBoolean();
        var session = new StreamingSession(new StreamingSession.Sink() {
            public void frame(byte[] opus, long sequence) {}
            public void close() { sinkClosed.set(true); }
        }, failure -> fail("Shutdown must not report a playback failure"));
        var field = StreamingSession.class.getDeclaredField("scheduler");
        field.setAccessible(true);
        var retired = (java.util.concurrent.ScheduledExecutorService) field.get(session);
        var entered = new CountDownLatch(1);
        var read = new java.util.concurrent.CompletableFuture<Integer>();
        var pipe = java.nio.channels.Pipe.open();
        StreamingSession replacement = null;
        try {
            retired.execute(() -> {
                entered.countDown();
                try { read.complete(pipe.source().read(java.nio.ByteBuffer.allocate(1))); }
                catch (Throwable error) { read.completeExceptionally(error); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            StreamingSession.shutdown();
            assertTrue(sinkClosed.get());
            assertTrue(retired.isShutdown());
            StreamingSession.enable();
            replacement = new StreamingSession(new StreamingSession.Sink() {
                public void frame(byte[] opus, long sequence) {}
                public void close() {}
            }, failure -> fail("Replacement must stay usable"));
            var current = (java.util.concurrent.ScheduledExecutorService) field.get(replacement);
            assertNotSame(retired, current);
            assertFalse(current.isShutdown());
            pipe.sink().write(java.nio.ByteBuffer.wrap(new byte[]{1}));
            assertEquals(1, read.get(5, TimeUnit.SECONDS), "Retired sender must finish its original NIO read normally");
            assertTrue(pipe.source().isOpen(), "Interrupting a cold read would close the shared archive channel");
            assertTrue(retired.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(7, current.submit(() -> 7).get(5, TimeUnit.SECONDS));
        } finally {
            pipe.source().close();
            pipe.sink().close();
            if (replacement != null) replacement.close();
            session.close();
            StreamingSession.shutdown();
        }
    }

    @Test void cancellingAnActiveLoadDoesNotInterruptTheSharedModArchiveReader() throws Exception {
        StreamingSession.enable();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var interrupted=new AtomicBoolean();var sinkClosed=new AtomicBoolean();
        FutureTask<Void> load=new FutureTask<>(()->{
            entered.countDown();
            try { release.await(); } catch(InterruptedException error) { interrupted.set(true); }
            return null;
        });
        Thread worker=new Thread(load,"Test archive reader");worker.start();
        var session=new StreamingSession(new StreamingSession.Sink(){
            public void frame(byte[] opus,long sequence) {}
            public void close() { sinkClosed.set(true); }
        },failure->fail("Manually cancelled playback must not report a failure"));
        var field=StreamingSession.class.getDeclaredField("load");field.setAccessible(true);field.set(session,load);
        try {
            assertTrue(entered.await(5,TimeUnit.SECONDS));session.close();
            assertTrue(load.isCancelled());assertTrue(sinkClosed.get());
            release.countDown();worker.join(5000);
            assertFalse(worker.isAlive());assertFalse(interrupted.get(),"Interrupting NIO class loading can close Forge's shared archive");
        } finally { release.countDown();worker.join(5000);session.close();StreamingSession.shutdown(); }
    }
}
