package com.eyecrasher.lazodiscs.voice;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LegacyAudioPacketTest {
    @Test void greetingStopAndFullFrameRoundTrip() throws Exception {
        assertEquals(LegacyAudioPacket.HELLO, LegacyAudioPacket.decode(LegacyAudioPacket.hello().encode()).kind);
        UUID id = UUID.randomUUID();
        assertEquals(id, LegacyAudioPacket.decode(LegacyAudioPacket.stop(id).encode()).source);
        var end = LegacyAudioPacket.decode(LegacyAudioPacket.end(id).encode());
        assertEquals(LegacyAudioPacket.END, end.kind);
        assertEquals(id, end.source);
        assertThrows(IOException.class, () -> LegacyAudioPacket.decode(Arrays.copyOf(end.encode(), end.encode().length + 1)));
        byte[] opus = new byte[4096]; new Random(1).nextBytes(opus);
        var sent = LegacyAudioPacket.frame(id, "minecraft:overworld", -123.5, 70, 9.25, 1024, 123456, opus);
        var read = LegacyAudioPacket.decode(sent.encode());
        assertEquals(id, read.source); assertEquals(sent.world, read.world);
        assertEquals(sent.x, read.x); assertEquals(sent.y, read.y); assertEquals(sent.z, read.z);
        assertEquals(sent.range, read.range); assertEquals(sent.sequence, read.sequence);
        assertArrayEquals(opus, read.opus);
        opus[0] ^= 1;
        assertNotEquals(opus[0], sent.opus[0], "Packet owns the provided audio buffer");
    }
    @Test void truncatedExtendedUnknownAndOversizedPayloadsAreRejected() {
        byte[] bytes = LegacyAudioPacket.frame(UUID.randomUUID(), "minecraft:overworld", 0, 64, 0, 64, 0, new byte[]{1,2,3}).encode();
        for (int i=0; i<bytes.length; i++) {
            byte[] partial=Arrays.copyOf(bytes,i);
            assertThrows(IOException.class, () -> LegacyAudioPacket.decode(partial));
        }
        assertThrows(IOException.class, () -> LegacyAudioPacket.decode(Arrays.copyOf(bytes,bytes.length+1)));
        assertThrows(IOException.class, () -> LegacyAudioPacket.decode(new byte[4609]));
        assertThrows(IOException.class, () -> LegacyAudioPacket.decode(new byte[]{0,2,1}));
        assertThrows(IOException.class, () -> LegacyAudioPacket.decode(new byte[]{0,1,99}));
        assertThrows(IOException.class, () -> LegacyAudioPacket.decode(new byte[]{0,1,1,0}));
    }
    @Test void invalidPositionsRangesAndAudioAreRejectedBeforeSending() {
        UUID id=UUID.randomUUID();
        for (double number : new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
            assertThrows(IllegalArgumentException.class, () -> LegacyAudioPacket.frame(id,"world",number,0,0,1,0,new byte[]{1}).encode());
        for (int range : new int[]{0,-1,1025})
            assertThrows(IllegalArgumentException.class, () -> LegacyAudioPacket.frame(id,"world",0,0,0,range,0,new byte[]{1}).encode());
        assertThrows(IllegalArgumentException.class, () -> LegacyAudioPacket.frame(id,"w".repeat(129),0,0,0,1,0,new byte[]{1}).encode());
        assertThrows(IllegalArgumentException.class, () -> LegacyAudioPacket.frame(id,"world",0,0,0,1,0,new byte[4097]).encode());
    }
    @Test void corruptRemoteCoordinatesAndLengthsAreRejected() {
        byte[] bytes=LegacyAudioPacket.frame(UUID.randomUUID(),"w",0,0,0,1,0,new byte[]{1}).encode();
        ByteBuffer.wrap(bytes).putDouble(22,Double.NaN);
        assertThrows(IOException.class, () -> LegacyAudioPacket.decode(bytes));
        byte[] badLength=LegacyAudioPacket.frame(UUID.randomUUID(),"w",0,0,0,1,0,new byte[]{1}).encode();
        // 3-byte header + UUID + modified UTF + XYZ + range + sequence.
        ByteBuffer.wrap(badLength).putShort(56,(short)4096);
        assertThrows(IOException.class, () -> LegacyAudioPacket.decode(badLength));
    }
}
