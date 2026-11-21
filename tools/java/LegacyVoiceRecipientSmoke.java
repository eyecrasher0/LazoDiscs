import com.eyecrasher.lazodiscs.voice.LegacyAudience;
import com.eyecrasher.lazodiscs.voice.LegacyAudioPacket;
import com.eyecrasher.lazodiscs.voice.LegacyNetwork;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Runs against the production adapter with controlled Minecraft players and the official Voice registry. */
public final class LegacyVoiceRecipientSmoke {
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        var registry = (Map<UUID, Object>) Class.forName("su.plo.voice.server.socket.SocketServerUDP")
                .getField("clients").get(null);
        var level = new ServerLevel();
        var listener = new ServerPlayer();
        var noHelper = new ServerPlayer();
        level.players().addAll(List.of(listener, noHelper));

        UUID source = UUID.randomUUID();
        var audience = new LegacyAudience();
        var center = new Vec3(0, 0, 0);
        LegacyNetwork.initialize();
        Greeting.receive(listener, LegacyAudioPacket.hello().encode());
        registry.put(noHelper.getUUID(), new Object());
        try {
            LegacyNetwork.broadcast(level, center, 16, frame(source, 0), audience);
            expect(listener, List.of());
            expect(noHelper, List.of());

            registry.put(listener.getUUID(), new Object());
            LegacyNetwork.broadcast(level, center, 16, frame(source, 1), audience);
            expect(listener, List.of(LegacyAudioPacket.FRAME));
            if (LegacyAudioPacket.decode(listener.connection.packets.get(0)).sequence != 1)
                throw new AssertionError("Wrong frame sequence");
            expect(noHelper, List.of());

            registry.remove(listener.getUUID());
            LegacyNetwork.broadcast(level, center, 16, frame(source, 2), audience);
            expect(listener, List.of(LegacyAudioPacket.FRAME));
            LegacyNetwork.broadcast(level, center, 16, LegacyAudioPacket.end(source), audience);
            LegacyNetwork.broadcast(level, center, 16, LegacyAudioPacket.stop(source), audience);
            expect(listener, List.of(LegacyAudioPacket.FRAME, LegacyAudioPacket.END, LegacyAudioPacket.STOP));
            expect(noHelper, List.of());
        } finally {
            registry.clear();
            LegacyNetwork.reset();
        }
        System.out.println(args[0] + " Voice 1 recipient selection passed");
    }

    private static LegacyAudioPacket frame(UUID source, int sequence) {
        return LegacyAudioPacket.frame(source, "minecraft:overworld", 0, 0, 0, 16, sequence, new byte[]{1});
    }

    private static void expect(ServerPlayer player, List<Integer> kinds) throws Exception {
        var received = new ArrayList<Integer>();
        for (byte[] payload : player.connection.packets) received.add(LegacyAudioPacket.decode(payload).kind);
        if (!kinds.equals(received)) throw new AssertionError("Expected " + kinds + ", received " + received);
    }
}
