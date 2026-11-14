package com.eyecrasher.lazodiscs.voice;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Versioned, bounded custom-payload contract for Voice 1 clients without static-source support. */
public final class LegacyAudioPacket {
    public static final String CHANNEL = "lazodiscs:legacy";
    public static final int HELLO = 1, FRAME = 2, STOP = 3;
    public final int kind;
    public final UUID source;
    public final String world;
    public final double x, y, z;
    public final int range;
    public final long sequence;
    public final byte[] opus;

    private LegacyAudioPacket(int kind, UUID source, String world, double x, double y, double z, int range, long sequence, byte[] opus) {
        this.kind = kind; this.source = source; this.world = world; this.x = x; this.y = y; this.z = z; this.range = range; this.sequence = sequence; this.opus = opus;
    }
    public static LegacyAudioPacket hello() { return new LegacyAudioPacket(HELLO, new UUID(0, 0), "", 0, 0, 0, 1, 0, new byte[0]); }
    public static LegacyAudioPacket frame(UUID id, String world, double x, double y, double z, int range, long sequence, byte[] opus) {
        return new LegacyAudioPacket(FRAME, id, world, x, y, z, range, sequence, opus.clone());
    }
    public static LegacyAudioPacket stop(UUID id) { return new LegacyAudioPacket(STOP, id, "", 0, 0, 0, 1, 0, new byte[0]); }
    public byte[] encode() {
        if (opus.length > 4096 || world.length() > 128 || range < 1 || range > 1024 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IllegalArgumentException("Invalid audio packet");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(0); // Forge SimpleChannel discriminator, also present on Fabric/Bukkit.
            out.writeByte(1); // LazoDiscs protocol version.
            out.writeByte(kind);
            if (kind != HELLO) { out.writeLong(source.getMostSignificantBits()); out.writeLong(source.getLeastSignificantBits()); }
            if (kind == FRAME) { out.writeUTF(world); out.writeDouble(x); out.writeDouble(y); out.writeDouble(z); out.writeShort(range); out.writeLong(sequence); out.writeShort(opus.length); out.write(opus); }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }
    public static LegacyAudioPacket decode(byte[] bytes) throws IOException {
        if (bytes.length > 4608) throw new IOException("Oversized audio packet");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readUnsignedByte() != 0 || in.readUnsignedByte() != 1) throw new IOException("Unknown audio protocol");
        int kind = in.readUnsignedByte();
        if (kind == HELLO) { if (in.available() != 0) throw new IOException("Invalid greeting"); return hello(); }
        UUID id = new UUID(in.readLong(), in.readLong());
        if (kind == STOP) { if (in.available() != 0) throw new IOException("Invalid stop"); return stop(id); }
        if (kind != FRAME) throw new IOException("Unknown audio packet");
        String world = in.readUTF(); double x = in.readDouble(), y = in.readDouble(), z = in.readDouble(); int range = in.readUnsignedShort(); long sequence = in.readLong(); int size = in.readUnsignedShort();
        if (size > 4096 || size != in.available() || world.length() > 128 || range < 1 || range > 1024 || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) throw new IOException("Invalid audio frame");
        byte[] opus = new byte[size]; in.readFully(opus);
        return frame(id, world, x, y, z, range, sequence, opus);
    }
}
