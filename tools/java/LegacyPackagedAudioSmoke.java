import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.format.transcoder.AudioChunkDecoder;
import com.sedmelluq.discord.lavaplayer.natives.opus.OpusEncoder;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManagers;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Final-JAR audio smoke on the game's actual JVM, without Minecraft or network access. */
public final class LegacyPackagedAudioSmoke {
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 255));
        return result.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static byte[] wave() {
        int sampleRate = 44100;
        int channels = 2;
        int dataBytes = sampleRate * channels * Short.BYTES;
        ByteBuffer out = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataBytes);
        out.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        out.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        out.putShort((short) 1).putShort((short) channels).putInt(sampleRate);
        out.putInt(sampleRate * channels * Short.BYTES);
        out.putShort((short) (channels * Short.BYTES)).putShort((short) 16);
        out.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataBytes);
        for (int index = 0; index < sampleRate; index++) {
            short value =
                    (short) Math.round(Math.sin(2 * Math.PI * 440 * index / sampleRate) * 10000);
            out.putShort(value).putShort(value);
        }
        return out.array();
    }

    public static void main(String[] arguments) throws Exception {
        check(
                arguments.length == 3,
                "Usage: LegacyPackagedAudioSmoke label expectedJar expectedJava");
        String label = arguments[0];
        Path jar = java.nio.file.Paths.get(arguments[1]).toRealPath();
        check(
                System.getProperty("java.specification.version").equals(arguments[2]),
                "Run with the actual target JVM: " + arguments[2]);
        verifyRecordAndUtilities();
        for (Class<?> type : new Class<?>[] {DefaultAudioPlayerManager.class, OpusEncoder.class}) {
            Path origin =
                    java.nio.file.Paths.get(
                                    type.getProtectionDomain()
                                            .getCodeSource()
                                            .getLocation()
                                            .toURI())
                            .toRealPath();
            check(origin.equals(jar), "Unexpected audio class origin: " + origin);
        }
        check(
                OpusEncoder.class.getResource("/natives/linux-x86-64/libconnector.so") != null,
                "Actual packaged Linux native resource is missing");
        Path wav = Files.createTempFile("lazo-opus-smoke-", ".wav");
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        AudioPlayer player = null;
        AudioChunkDecoder decoder = null;
        AtomicReference<AudioTrackEndReason> ended = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            Files.write(wav, wave());
            manager.getConfiguration().setOutputFormat(StandardAudioDataFormats.DISCORD_OPUS);
            manager.setFrameBufferDuration(500);
            manager.setPlayerCleanupThreshold(30_000L);
            AudioSourceManagers.registerLocalSource(manager);
            AudioItem item = manager.loadItemSync(wav.toAbsolutePath().toString());
            check(item instanceof AudioTrack, "WAV did not load as an audio track: " + item);
            AudioTrack track = (AudioTrack) item;
            check(track.getSourceManager().getSourceName().equals("local"), "Unexpected source");
            check(
                    track.getDuration() >= 990 && track.getDuration() <= 1010,
                    "Unexpected WAV duration: " + track.getDuration());
            player = manager.createPlayer();
            player.addListener(
                    new AudioEventAdapter() {
                        @Override
                        public void onTrackEnd(
                                AudioPlayer player, AudioTrack track, AudioTrackEndReason reason) {
                            ended.set(reason);
                        }

                        @Override
                        public void onTrackException(
                                AudioPlayer player, AudioTrack track, FriendlyException error) {
                            failure.set(error);
                        }

                        @Override
                        public void onTrackStuck(
                                AudioPlayer player, AudioTrack track, long threshold) {
                            failure.set(new AssertionError("Track stuck for " + threshold + "ms"));
                        }
                    });
            decoder = StandardAudioDataFormats.DISCORD_OPUS.createDecoder();
            ShortBuffer pcm =
                    ByteBuffer.allocateDirect(
                                    StandardAudioDataFormats.DISCORD_OPUS.totalSampleCount()
                                            * Short.BYTES)
                            .order(ByteOrder.nativeOrder())
                            .asShortBuffer();
            check(player.startTrack(track, false), "Could not start WAV track");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int frames = 0;
            int bytes = 0;
            int nonzeroSamples = 0;
            long lastTimecode = -1;
            byte[] last = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (ended.get() == null && failure.get() == null && System.nanoTime() < deadline) {
                AudioFrame frame;
                try {
                    frame = player.provide(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException timeout) {
                    continue;
                }
                if (frame == null || frame.isTerminator()) continue;
                check(
                        frame.getFormat().equals(StandardAudioDataFormats.DISCORD_OPUS),
                        "Unexpected output format: " + frame.getFormat().codecName());
                byte[] data = frame.getData();
                check(
                        data.length > 0 && frame.getDataLength() == data.length,
                        "Empty/inconsistent Opus frame");
                decoder.decode(data, pcm);
                check(
                        pcm.remaining() == StandardAudioDataFormats.DISCORD_OPUS.totalSampleCount(),
                        "Unexpected decoded PCM frame size: " + pcm.remaining());
                while (pcm.hasRemaining()) if (pcm.get() != 0) nonzeroSamples++;
                digest.update(data);
                frames++;
                bytes += data.length;
                lastTimecode = frame.getTimecode();
                last = data;
            }
            check(failure.get() == null, "Playback failure: " + failure.get());
            check(
                    ended.get() == AudioTrackEndReason.FINISHED,
                    "Track did not finish: " + ended.get());
            check(frames >= 40 && frames <= 60, "Unexpected frame count for 1sec WAV: " + frames);
            check(bytes > 0 && last != null && last.length > 0, "No final Opus bytes");
            check(lastTimecode >= 800, "Last frame was truncated: " + lastTimecode);
            check(nonzeroSamples > 0, "Decoded audio was empty/silent");
            System.out.printf(
                    "PASS %s reason=%s frames=%d opusBytes=%d lastTimecodeMs=%d lastBytes=%d"
                            + " lastPrefix=%s decodedNonzeroSamples=%d opusSha256=%s%n",
                    label,
                    ended.get(),
                    frames,
                    bytes,
                    lastTimecode,
                    last.length,
                    hex(java.util.Arrays.copyOf(last, Math.min(16, last.length))),
                    nonzeroSamples,
                    hex(digest.digest()));
        } finally {
            if (decoder != null) decoder.close();
            if (player != null) player.destroy();
            manager.shutdown();
            Files.deleteIfExists(wav);
        }
    }

    private static void verifyRecordAndUtilities() throws Exception {
        java.util.UUID id = java.util.UUID.fromString("109d83e8-7d67-46af-8479-e9c0d12a3008");
        com.eyecrasher.lazodiscs.data.CustomDiscData first =
                new com.eyecrasher.lazodiscs.data.CustomDiscData(
                        "https://example.com/test.ogg", "Test", 48, 1.0F, id);
        com.eyecrasher.lazodiscs.data.CustomDiscData second =
                new com.eyecrasher.lazodiscs.data.CustomDiscData(
                        first.url(), first.title(), first.range(), first.volume(), first.id());
        check(
                first.equals(second) && first.hashCode() == second.hashCode(),
                "Backported record equality/hash failed");
        check(
                first.toString().contains("Test") && first.id().equals(id),
                "Backported record access/string failed");
        check(
                !first.equals(
                        new com.eyecrasher.lazodiscs.data.CustomDiscData(
                                first.url(), "Other", 48, 1.0F, id)),
                "Backported record incorrectly equates different values");
        try {
            new com.eyecrasher.lazodiscs.data.CustomDiscData(null, "Test", 48, 1.0F, id);
            throw new AssertionError("Backported compact constructor lost its validation");
        } catch (NullPointerException expected) {
            // The original record's compact constructor must still run.
        }
        // Execute a real script so downgraded Rhino is checked beyond class headers.
        Class<?> context = Class.forName("org.mozilla.javascript.Context");
        Object js = context.getMethod("enter").invoke(null);
        try {
            Object scope = context.getMethod("initStandardObjects").invoke(js);
            Object answer = context.getMethod("evaluateString", Class.forName("org.mozilla.javascript.Scriptable"), String.class, String.class, int.class, Object.class)
                    .invoke(js, scope, "[1,2,3].map(function(x){return x*2;}).join(',')", "lazo-smoke", 1, null);
            check("2,4,6".equals(answer.toString()), "Rhino script failed on the target JVM");
        } finally { context.getMethod("exit").invoke(null); }
        String prefix = "com.eyecrasher.lazodiscs.shadow.";
        Class<?> logger = Class.forName(prefix + "org.slf4j.LoggerFactory");
        Object logging = logger.getMethod("getILoggerFactory").invoke(null);
        check(
                logging.getClass().getName().endsWith("JDK14LoggerFactory"),
                "Private SLF4J service provider missing");
        Class<?> parser = Class.forName(prefix + "com.google.gson.JsonParser");
        Object parsed =
                parser.getMethod("parseString", String.class).invoke(null, "{\"value\":42}");
        check(parsed.toString().contains("42"), "Private Gson failed on the target JVM");
        Class<?> mapper = Class.forName(prefix + "com.fasterxml.jackson.databind.ObjectMapper");
        Object tree =
                mapper.getMethod("readTree", String.class)
                        .invoke(mapper.getConstructor().newInstance(), "{\"value\":42}");
        check(tree.toString().contains("42"), "Private Jackson failed on the target JVM");
    }
}
