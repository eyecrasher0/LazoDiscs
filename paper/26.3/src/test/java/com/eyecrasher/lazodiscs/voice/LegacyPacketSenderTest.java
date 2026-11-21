package com.eyecrasher.lazodiscs.voice;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyPacketSenderTest {
    @Test void naturalEndFollowsAllFramesAlreadyQueuedOnTheGameThread() {
        var pending = new ArrayDeque<Runnable>();
        var received = new ArrayList<LegacyAudioPacket>();
        UUID id = UUID.randomUUID();
        var sender = new LegacyPacketSender(id, pending::add,
                (opus, sequence) -> LegacyAudioPacket.frame(id,"world",0,0,0,64,sequence,opus), received::add);
        byte[] data = {1};
        sender.frame(data,0); data[0]=99;
        sender.frame(new byte[]{2},1); sender.frame(new byte[]{3},2);
        sender.finish(); sender.finish(); sender.frame(new byte[]{4},3);
        while (!pending.isEmpty()) pending.remove().run();
        assertEquals(List.of(2,2,2,4), received.stream().map(packet->packet.kind).toList());
        assertEquals(List.of(0L,1L,2L), received.subList(0,3).stream().map(packet->packet.sequence).toList());
        assertArrayEquals(new byte[]{1},received.get(0).opus);
        assertEquals(id,received.get(3).source);
    }

    @Test void manualStopDropsQueuedFramesAndPendingNaturalEnd() {
        var pending = new ArrayDeque<Runnable>();
        var received = new ArrayList<LegacyAudioPacket>();
        UUID id = UUID.randomUUID();
        var sender = new LegacyPacketSender(id,pending::add,
                (opus,sequence)->LegacyAudioPacket.frame(id,"world",0,0,0,64,sequence,opus),received::add);
        sender.frame(new byte[]{1},0); sender.finish(); sender.close(); sender.close();
        while (!pending.isEmpty()) pending.remove().run();
        assertEquals(1,received.size());assertEquals(LegacyAudioPacket.STOP,received.get(0).kind);
    }
}
