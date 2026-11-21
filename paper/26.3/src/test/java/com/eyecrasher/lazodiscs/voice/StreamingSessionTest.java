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

    @Test void explicitStopAfterRealEofReachesThePriorListenerOutsideTheFrameRange() throws Exception {
        StreamingSession.enable();
        var manager = new CooperativeAudioPlayerManager();
        var player = manager.createPlayer();
        var track = new com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack(
                new com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo("fixture", "test", 20, "fixture", false, null)) {
            @Override public void process(com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor executor) throws Exception {
                executor.getAudioBuffer().consume(new com.sedmelluq.discord.lavaplayer.track.playback.ImmutableAudioFrame(
                        0, new byte[]{3,7,11},100,com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats.DISCORD_OPUS));
                executor.waitOnEnd();
            }
        };
        var source = java.util.UUID.randomUUID();
        var listener = java.util.UUID.randomUUID();
        var audible = new java.util.ArrayList<java.util.UUID>();audible.add(listener);
        var received = new java.util.ArrayList<Integer>();
        var audience = new LegacyAudience();
        var completed = new AtomicBoolean();
        var sender = new LegacyPacketSender(source, Runnable::run,
                (opus, sequence) -> LegacyAudioPacket.frame(source,"world",0,0,0,64,sequence,opus),
                packet -> audience.deliver(packet,audible,id -> id.equals(listener) ? id : null,(id,bytes) -> {
                    assertEquals(listener,id);
                    try { received.add(LegacyAudioPacket.decode(bytes).kind); }
                    catch(java.io.IOException error) { throw new AssertionError(error); }
                }));
        var session = new StreamingSession(sender, failure -> { assertNull(failure);completed.set(true); });
        try {
            AudioPlaybackPreparation.start(player,track,null);
            var playback = StreamingSession.class.getDeclaredField("playback");playback.setAccessible(true);
            playback.set(session,new LavaPcmFeeder.StreamingPlayback(player,track));
            var tick = StreamingSession.class.getDeclaredMethod("tick", java.util.UUID.class);tick.setAccessible(true);
            tick.invoke(session,source);
            assertEquals(java.util.List.of(LegacyAudioPacket.FRAME),received);
            audible.clear(); // A listener moves out of range after hearing the source.
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(!completed.get()&&System.nanoTime()<deadline){tick.invoke(session,source);Thread.sleep(1);}
            assertTrue(completed.get());
            assertEquals(java.util.List.of(LegacyAudioPacket.FRAME,LegacyAudioPacket.END),received);
            session.close();session.close();
            assertEquals(java.util.List.of(LegacyAudioPacket.FRAME,LegacyAudioPacket.END,LegacyAudioPacket.STOP),received,
                    "Manual stop after EOF must still reach the original audience exactly once");
            assertNull(playback.get(session),"A retained stop handle must release the closed decoder");
        } finally { session.close();player.destroy();manager.shutdown();StreamingSession.shutdown(); }
    }
}
