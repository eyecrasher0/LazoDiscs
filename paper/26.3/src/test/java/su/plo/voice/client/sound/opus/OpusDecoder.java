package su.plo.voice.client.sound.opus;
public final class OpusDecoder {
    public static int opened, closed;
    public OpusDecoder(int rate, int frameSize, int maximumPayload) { opened++; }
    public byte[] decode(byte[] opus) { return new byte[]{1,2}; }
    public void close() { closed++; }
}
