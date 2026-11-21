package com.eyecrasher.lazodiscs.voice;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.openal.AL10;
import org.lwjgl.system.MemoryUtil;
import su.plo.voice.client.VoiceClient;
import su.plo.voice.client.sound.opus.OpusDecoder;
import static org.junit.jupiter.api.Assertions.*;

/** Checks the client boundary: sound-context dispatch, user controls and bounded source lifetimes. */
class LegacyClientAudioTest {
    private final VoiceClient.Engine engine=VoiceClient.ENGINE;
    @BeforeEach void setup() {
        LegacyClientAudio.prune(false);engine.flush();engine.sources.clear();
        engine.context=1;
        engine.lazyContext=0;
        AL10.resetContext();
        AL10.allocations=0;AL10.deletions=0;AL10.processed=false;
        MemoryUtil.allocations=0;
        VoiceClient.CONFIG.voiceVolume.value=1.0;VoiceClient.CONFIG.speakerMuted.value=false;
        OpusDecoder.opened=0;OpusDecoder.closed=0;
    }
    @AfterEach void close() { LegacyClientAudio.prune(false);engine.flush(); }
    private LegacyAudioPacket frame(UUID id,long sequence) {
        return LegacyAudioPacket.frame(id,"minecraft:overworld",1,2,3,64,sequence,new byte[]{1});
    }
    private void accept(LegacyAudioPacket packet) { LegacyClientAudio.accept(packet,"minecraft:overworld",p->p); }
    @Test void audioRunsOnlyInsideVoiceSoundContextAndObeysMuteAndVolume() {
        UUID id=UUID.randomUUID();accept(frame(id,0));
        assertTrue(engine.sources.isEmpty(),"Network receiver must not touch OpenAL directly");
        engine.flush();var source=engine.sources.get(0);
        assertEquals(1,source.writes);assertFalse(source.relative);assertFalse(source.looping);
        assertEquals(64,source.distance);assertEquals(32,source.fade);
        VoiceClient.CONFIG.voiceVolume.value=.25;accept(frame(id,1));engine.flush();
        assertEquals(.25F,source.volume);
        VoiceClient.CONFIG.speakerMuted.value=true;accept(frame(id,2));engine.flush();
        assertEquals(0,source.volume);assertEquals(1,OpusDecoder.opened);
    }
    @Test void duplicateAndOldFramesDoNotReachDecoderOrAudioSource() {
        UUID id=UUID.randomUUID();accept(frame(id,10));engine.flush();
        accept(frame(id,10));accept(frame(id,9));engine.flush();assertEquals(1,engine.sources.get(0).writes);
        accept(frame(id,11));engine.flush();assertEquals(2,engine.sources.get(0).writes);
    }
    @Test void firstFrameInitializesALazyVoiceSoundContextWithoutDroppingAudio() {
        engine.context=0;engine.lazyContext=7;
        accept(LegacyAudioPacket.stop(UUID.randomUUID()));engine.flush();
        LegacyClientAudio.prune(true);engine.flush();
        assertEquals(0,engine.context,"Cleanup must not start an idle Voice engine");
        UUID id=UUID.randomUUID();accept(frame(id,0));engine.flush();
        assertEquals(7,engine.context,"Voice creates its OpenAL context on the first source");
        assertEquals(1,engine.sources.size());assertEquals(1,engine.sources.get(0).writes);
        assertEquals(1,OpusDecoder.opened);
        accept(LegacyAudioPacket.stop(id));engine.flush();
        assertTrue(engine.sources.get(0).closed);assertTrue(MemoryUtil.LIVE.isEmpty());
    }
    @Test void stopDimensionChangeAndDisconnectReleaseDecoderAndSource() {
        UUID id=UUID.randomUUID();accept(frame(id,0));engine.flush();
        accept(LegacyAudioPacket.stop(id));engine.flush();
        assertTrue(engine.sources.get(0).closed);assertEquals(1,OpusDecoder.closed);
        accept(frame(id,1));engine.flush();
        LegacyClientAudio.accept(frame(id,2),"minecraft:the_nether",p->p);engine.flush();
        assertTrue(engine.sources.get(1).closed);assertEquals(2,OpusDecoder.closed);
        accept(frame(id,3));engine.flush();LegacyClientAudio.prune(false);engine.flush();
        assertTrue(engine.sources.get(2).closed);assertEquals(OpusDecoder.opened,OpusDecoder.closed);
    }
    @Test void serverCannotAllocateUnboundedNativeSources() {
        for(int i=0;i<40;i++)accept(frame(UUID.randomUUID(),0));engine.flush();
        assertEquals(32,engine.sources.size());assertEquals(32,OpusDecoder.opened);
        LegacyClientAudio.prune(false);engine.flush();assertEquals(32,OpusDecoder.closed);
    }
    @Test void changingDimensionRetiresAudioWithoutReceivingAnotherFrame() {
        UUID id=UUID.randomUUID();accept(frame(id,0));engine.flush();
        LegacyClientAudio.prune(true,"minecraft:the_nether");engine.flush();
        assertTrue(engine.sources.get(0).closed);assertEquals(1,OpusDecoder.closed);
        assertTrue(MemoryUtil.LIVE.isEmpty());assertTrue(AL10.buffers.isEmpty());
    }
    @Test void naturalEndDrainsQueuedAudioWhileManualStopRemainsImmediate() throws Exception {
        UUID id=UUID.randomUUID();accept(frame(id,0));accept(frame(id,1));engine.flush();
        var source=engine.sources.get(0);
        accept(LegacyAudioPacket.decode(LegacyAudioPacket.end(id).encode()));engine.flush();
        LegacyClientAudio.prune(true);engine.flush();
        assertFalse(source.closed,"Natural EOF must preserve the last queued PCM");
        accept(frame(id,2));engine.flush();assertEquals(2,source.writes);
        AL10.processed=true;LegacyClientAudio.prune(true);engine.flush();
        assertTrue(source.closed);assertTrue(MemoryUtil.LIVE.isEmpty());
        AL10.processed=false;
        UUID next=UUID.randomUUID();accept(frame(next,0));engine.flush();
        accept(LegacyAudioPacket.end(next));accept(LegacyAudioPacket.stop(next));engine.flush();
        assertTrue(engine.sources.get(1).closed,"Extraction must stop queued audio immediately");
        assertTrue(MemoryUtil.LIVE.isEmpty());
    }
    @Test void stalledAndPlayingSourcesReuseTheirNativeBuffers() {
        UUID id=UUID.randomUUID();
        for(int i=0;i<100;i++){accept(frame(id,i));engine.flush();}
        assertEquals(8,AL10.allocations);
        assertEquals(1,MemoryUtil.allocations);
        assertEquals(1,MemoryUtil.LIVE.size());
        AL10.processed=true;
        for(int i=100;i<2100;i++){accept(frame(id,i));engine.flush();}
        assertEquals(2008,engine.sources.get(0).writes);
        assertEquals(8,AL10.allocations);
        assertEquals(1,MemoryUtil.allocations);
        accept(LegacyAudioPacket.stop(id));engine.flush();
        assertEquals(8,AL10.deletions);
        assertTrue(AL10.buffers.isEmpty());
        assertTrue(MemoryUtil.LIVE.isEmpty());
    }
    @Test void resumingAnUnderrunDoesNotReplayPreviouslyConsumedPcm() {
        UUID id=UUID.randomUUID();
        for(int i=0;i<8;i++){accept(frame(id,i));engine.flush();}
        int pointer=engine.sources.get(0).getPointer();
        assertEquals(8,AL10.alGetSourcei(pointer,0x1015));
        AL10.processed=true;accept(frame(id,8));engine.flush();
        assertEquals(1,AL10.alGetSourcei(pointer,0x1015),"Only the new frame may be queued after every old buffer was consumed");
        assertEquals(8,AL10.allocations,"Processed storage must be reused");
        accept(LegacyAudioPacket.stop(id));engine.flush();
        assertTrue(AL10.buffers.isEmpty());assertTrue(MemoryUtil.LIVE.isEmpty());
    }
    @Test void aStalledNaturalTailStillExpiresAndReleasesItsNativeStorage() throws Exception {
        UUID id=UUID.randomUUID();accept(frame(id,0));accept(LegacyAudioPacket.end(id));engine.flush();
        var channels=LegacyClientAudio.class.getDeclaredField("CHANNELS");channels.setAccessible(true);
        Object channel=((java.util.Map<?,?>)channels.get(null)).get(id);
        var lastFrame=channel.getClass().getDeclaredField("lastFrame");lastFrame.setAccessible(true);
        lastFrame.setLong(channel,System.nanoTime()-3_000_000_000L);
        LegacyClientAudio.prune(true);engine.flush();
        assertTrue(engine.sources.get(0).closed);assertTrue(MemoryUtil.LIVE.isEmpty());assertTrue(AL10.buffers.isEmpty());
    }
    @Test void restartingVoiceSoundContextRecreatesSourcesWithoutDeletingReusedIds() {
        UUID id=UUID.randomUUID();accept(frame(id,0));engine.flush();
        var previous=engine.sources.get(0);
        AL10.resetContext();
        int unrelated=AL10.alGenBuffers();
        org.lwjgl.openal.AL.capabilities=new Object();accept(frame(id,1));engine.flush();
        assertEquals(2,engine.sources.size());assertEquals(1,previous.writes);
        assertFalse(previous.closed,"The destroyed context's source ID can belong to another sound now");
        assertTrue(AL10.buffers.contains(unrelated));
        assertEquals(0,AL10.deletions);
        assertEquals(1,MemoryUtil.LIVE.size());
        assertEquals(1,engine.sources.get(1).writes);assertEquals(1,OpusDecoder.closed);
        engine.context=0;LegacyClientAudio.prune(true);engine.flush();
        assertEquals(2,OpusDecoder.closed);
        assertTrue(MemoryUtil.LIVE.isEmpty());
        accept(frame(id,2));engine.flush();assertEquals(2,engine.sources.size());
    }
}
