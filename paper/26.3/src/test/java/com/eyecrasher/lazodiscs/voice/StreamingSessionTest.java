package com.eyecrasher.lazodiscs.voice;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StreamingSessionTest {
    @Test void cancellingAnActiveLoadDoesNotInterruptTheSharedModArchiveReader() throws Exception {
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
        } finally { release.countDown();worker.join(5000);session.close(); }
    }
}
