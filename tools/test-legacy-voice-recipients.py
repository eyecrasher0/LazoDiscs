#!/usr/bin/env python3
"""Run both Voice 1 adapters against controlled loader I/O and pinned official session registries."""
import argparse
import hashlib
import json
import os
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com/eyecrasher/lazodiscs/voice"

# Minecraft and the loader supply players, scheduling and payload I/O. The actual
# adapters, payload format, audience state and official session lookup compile unchanged.
GAME = {
    "com.eyecrasher.lazodiscs.LazoDiscs": """
        public final class LazoDiscs {
            public static final Log LOGGER = new Log();
            public static final class Log {
                public void warn(String text, Throwable cause) { throw new AssertionError(text, cause); }
            }
        }""",
    "net.minecraft.resources.ResourceLocation": """
        public record ResourceLocation(String namespace, String path) {}""",
    "net.minecraft.world.phys.Vec3": """
        public record Vec3(double x, double y, double z) {
            public double distanceToSqr(Vec3 other) {
                double dx = x - other.x, dy = y - other.y, dz = z - other.z;
                return dx * dx + dy * dy + dz * dz;
            }
        }""",
    "net.minecraft.server.level.ServerLevel": """
        import java.util.*;
        public final class ServerLevel {
            private final List<ServerPlayer> players = new ArrayList<>();
            public List<ServerPlayer> players() { return players; }
            public ServerLevel getServer() { return this; }
            public ServerLevel getPlayerList() { return this; }
            public ServerPlayer getPlayer(UUID id) {
                return players.stream().filter(p -> p.getUUID().equals(id)).findFirst().orElse(null);
            }
            public void execute(Runnable task) { task.run(); }
        }""",
    "net.minecraft.server.level.ServerPlayer": """
        import java.util.*;
        import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
        import net.minecraft.world.phys.Vec3;
        public final class ServerPlayer {
            private final UUID id = UUID.randomUUID();
            public final Connection connection = new Connection();
            public UUID getUUID() { return id; }
            public Vec3 position() { return new Vec3(0, 0, 0); }
            public static final class Connection {
                public final List<byte[]> packets = new ArrayList<>();
                public void send(ClientboundCustomPayloadPacket packet) { packets.add(packet.buffer().bytes()); }
            }
        }""",
    "net.minecraft.network.FriendlyByteBuf": """
        public final class FriendlyByteBuf {
            private byte[] data;
            public FriendlyByteBuf(byte[] data) { this.data = data.clone(); }
            public byte[] bytes() { return data.clone(); }
            public int readableBytes() { return data.length; }
            public void readBytes(byte[] into) { readBytes(into, 0, into.length); }
            public void readBytes(byte[] into, int offset, int length) { System.arraycopy(data, 0, into, offset, length); }
            public void writeBytes(byte[] from) { writeBytes(from, 0, from.length); }
            public void writeBytes(byte[] from, int offset, int length) {
                data = java.util.Arrays.copyOfRange(from, offset, offset + length);
            }
        }""",
    "net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket": """
        import net.minecraft.network.FriendlyByteBuf;
        import net.minecraft.resources.ResourceLocation;
        public record ClientboundCustomPayloadPacket(ResourceLocation channel, FriendlyByteBuf buffer) {}""",
}

FABRIC = {
    "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking": """
        import net.minecraft.resources.ResourceLocation;
        import net.minecraft.network.FriendlyByteBuf;
        import net.minecraft.server.level.*;
        public final class ServerPlayNetworking {
            private static Receiver receiver;
            public interface Receiver {
                void receive(ServerLevel server, ServerPlayer player, Object handler, FriendlyByteBuf input, Object sender);
            }
            public static void registerGlobalReceiver(ResourceLocation channel, Receiver next) { receiver = next; }
            public static void receive(ServerPlayer player, byte[] bytes) {
                receiver.receive(new ServerLevel(), player, null, new FriendlyByteBuf(bytes), null);
            }
            public static void send(ServerPlayer player, ResourceLocation channel, FriendlyByteBuf bytes) {
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket(channel, bytes));
            }
        }""",
    "net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents": """
        import java.util.function.BiConsumer;
        import net.minecraft.server.level.*;
        public final class ServerPlayConnectionEvents {
            public static final Disconnect DISCONNECT = new Disconnect();
            public static final class Handler { public ServerPlayer player; }
            public static final class Disconnect { public void register(BiConsumer<Handler, ServerLevel> handler) {} }
        }""",
    "net.fabricmc.fabric.api.networking.v1.PacketByteBufs": """
        import net.minecraft.network.FriendlyByteBuf;
        public final class PacketByteBufs {
            public static FriendlyByteBuf create() { return new FriendlyByteBuf(new byte[0]); }
        }""",
    "Greeting": """
        public final class Greeting {
            public static void receive(net.minecraft.server.level.ServerPlayer player, byte[] bytes) {
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.receive(player, bytes);
            }
        }""",
}

FORGE = {
    "io.netty.buffer.Unpooled": """
        public final class Unpooled { public static byte[] wrappedBuffer(byte[] bytes) { return bytes; } }""",
    "net.minecraftforge.network.NetworkRegistry": """
        import java.util.function.*;
        import net.minecraft.resources.ResourceLocation;
        import net.minecraftforge.network.simple.SimpleChannel;
        public final class NetworkRegistry {
            public static SimpleChannel newSimpleChannel(ResourceLocation channel, Supplier<String> version,
                                                         Predicate<String> server, Predicate<String> client) {
                return new SimpleChannel();
            }
        }""",
    "net.minecraftforge.network.NetworkEvent": """
        import net.minecraft.server.level.ServerPlayer;
        public final class NetworkEvent {
            public record Context(ServerPlayer sender) {
                public ServerPlayer getSender() { return sender; }
                public void enqueueWork(Runnable task) { task.run(); }
                public void setPacketHandled(boolean handled) {}
            }
        }""",
    "net.minecraftforge.network.simple.SimpleChannel": """
        import java.util.Arrays;
        import java.util.function.*;
        import net.minecraft.network.FriendlyByteBuf;
        import net.minecraft.server.level.ServerPlayer;
        import net.minecraftforge.network.NetworkEvent.Context;
        public final class SimpleChannel {
            private static BiConsumer<ServerPlayer, byte[]> receiver;
            public <T> void registerMessage(int discriminator, Class<T> type, BiConsumer<T, FriendlyByteBuf> encoder,
                                            Function<FriendlyByteBuf, T> decoder, BiConsumer<T, Supplier<Context>> handler) {
                receiver = (player, bytes) -> {
                    if (bytes[0] != discriminator) throw new AssertionError("Wrong discriminator");
                    var buffer = new FriendlyByteBuf(Arrays.copyOfRange(bytes, 1, bytes.length));
                    handler.accept(decoder.apply(buffer), () -> new Context(player));
                };
            }
            public static void receive(ServerPlayer player, byte[] bytes) { receiver.accept(player, bytes); }
        }""",
    "net.minecraftforge.eventbus.api.SubscribeEvent": "public @interface SubscribeEvent {}",
    "net.minecraftforge.event.entity.player.PlayerEvent": """
        import net.minecraft.server.level.ServerPlayer;
        public final class PlayerEvent {
            public record PlayerLoggedOutEvent(ServerPlayer player) {
                public ServerPlayer getPlayer() { return player; }
            }
        }""",
    "net.minecraftforge.fml.common.Mod": """
        public @interface Mod { @interface EventBusSubscriber { String modid(); } }""",
    "net.minecraftforge.api.distmarker.Dist": "public enum Dist { CLIENT }",
    "net.minecraftforge.fml.DistExecutor": """
        public final class DistExecutor {
            public static void unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist side,
                                               java.util.function.Supplier<Runnable> work) {
                throw new AssertionError("The fixture runs on the server");
            }
        }""",
    "com.eyecrasher.lazodiscs.voice.LegacyClient": """
        final class LegacyClient { static void accept(LegacyAudioPacket packet) { throw new AssertionError(); } }""",
    "Greeting": """
        public final class Greeting {
            public static void receive(net.minecraft.server.level.ServerPlayer player, byte[] bytes) {
                net.minecraftforge.network.simple.SimpleChannel.receive(player, bytes);
            }
        }""",
}


def verify(platform, minecraft, voice, javac, java, boundary):
    rows = json.loads((ROOT / "compatibility.json").read_text())["entries"]
    pin = next(row["voice"] for row in rows if row["platform"] == platform and row["minecraft"] == minecraft)
    if pin["generation"] != 1 or hashlib.sha512(voice.read_bytes()).hexdigest() != pin["sha512"]:
        raise ValueError(f"{platform} {minecraft} requires the pinned official {pin['version']} JAR")
    with tempfile.TemporaryDirectory(prefix=f"lazodiscs-recipients-{platform}-") as folder:
        temp = Path(folder)
        sources = [
            ROOT / "common/src/main/java" / PACKAGE / "LegacyAudioPacket.java",
            ROOT / "common/src/main/java" / PACKAGE / "LegacyAudience.java",
            ROOT / "common/variants/pv1/java" / PACKAGE / "LegacyVoiceSessions.java",
            ROOT / "common/variants/pv1" / platform / PACKAGE / "LegacyNetwork.java",
            ROOT / "tools/java/LegacyVoiceRecipientSmoke.java",
        ]
        for name, body in (GAME | boundary).items():
            path = temp / (name.replace(".", "/") + ".java")
            path.parent.mkdir(parents=True, exist_ok=True)
            package = f"package {name.rsplit('.', 1)[0]};\n" if "." in name else ""
            path.write_text(package + body.strip() + "\n", encoding="utf-8")
            sources.append(path)
        classes = temp / "classes"
        subprocess.run([javac, "--release", "16", "-d", str(classes), *map(str, sources)], check=True)
        subprocess.run([java, "-cp", os.pathsep.join(map(str, (classes, voice))), "LegacyVoiceRecipientSmoke", platform], check=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fabric-voice", required=True, type=Path, help="Official pinned Fabric 1.17 Voice JAR")
    parser.add_argument("--forge-voice", required=True, type=Path, help="Official pinned Forge 1.18.1 Voice JAR")
    parser.add_argument("--java-home", type=Path, help="JDK 17 or later")
    options = parser.parse_args()
    java = str(options.java_home / "bin/java") if options.java_home else "java"
    javac = str(options.java_home / "bin/javac") if options.java_home else "javac"
    verify("fabric", "1.17", options.fabric_voice, javac, java, FABRIC)
    verify("forge", "1.18.1", options.forge_voice, javac, java, FORGE)
