package com.eyecrasher.lazodiscs.voice;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyAudienceTest {
    @Test void disconnectedChurnDoesNotAccumulateButConnectedOutOfRangeListenersStillReceiveStop() {
        var audience=new LegacyAudience();var online=new HashMap<UUID,UUID>();var source=UUID.randomUUID();
        var received=new HashMap<UUID,List<Integer>>();
        java.util.function.BiConsumer<UUID,byte[]> send=(id,bytes)->{
            try { received.computeIfAbsent(id,key->new ArrayList<>()).add(LegacyAudioPacket.decode(bytes).kind); }
            catch(java.io.IOException error){throw new AssertionError(error);}
        };
        UUID current=null;
        for(int i=0;i<10000;i++) {
            online.clear();current=UUID.randomUUID();online.put(current,current);
            audience.deliver(LegacyAudioPacket.frame(source,"world",0,0,0,64,i,new byte[]{1}),List.of(current),online::get,send);
        }
        UUID listener=current;
        audience.deliver(LegacyAudioPacket.end(source),List.of(),online::get,send);
        audience.deliver(LegacyAudioPacket.stop(source),List.of(),online::get,send);
        assertEquals(List.of(2,4,3),received.get(listener));
        assertEquals(1,received.values().stream().filter(kinds->kinds.contains(3)).count());
        int before=received.get(listener).size();
        audience.deliver(LegacyAudioPacket.stop(source),List.of(),online::get,send);
        assertEquals(before,received.get(listener).size(),"STOP releases its recipient set");
    }
    @Test void aGreetingWithoutAnEligibleVoiceSessionReceivesNoAudioAndCanBecomeEligibleLater() {
        var audience=new LegacyAudience();var source=UUID.randomUUID();var listener=UUID.randomUUID();
        var online=Map.of(listener,listener);var eligible=new ArrayList<UUID>();var received=new ArrayList<Integer>();
        java.util.function.BiConsumer<UUID,byte[]> send=(id,bytes)->{
            try{received.add(LegacyAudioPacket.decode(bytes).kind);}catch(java.io.IOException error){throw new AssertionError(error);}
        };
        audience.deliver(LegacyAudioPacket.frame(source,"world",0,0,0,64,0,new byte[]{1}),eligible,online::get,send);
        assertTrue(received.isEmpty());
        eligible.add(listener);
        audience.deliver(LegacyAudioPacket.frame(source,"world",0,0,0,64,1,new byte[]{1}),eligible,online::get,send);
        eligible.clear();
        audience.deliver(LegacyAudioPacket.frame(source,"world",0,0,0,64,2,new byte[]{1}),eligible,online::get,send);
        audience.deliver(LegacyAudioPacket.stop(source),eligible,online::get,send);
        assertEquals(List.of(2,3),received,"Losing Voice eligibility blocks frames while allowing old PCM cleanup");
    }
}
