package co.xenastudios.xbackup.store;

import java.util.Arrays;

/**
 * A region file ({@code .mca}) as a snapshot keeps it: the file's length, and for each of
 * its 1,024 chunks where it sits, its timestamp, and the blob holding its record (the
 * 4-byte length, compression byte and payload, exactly as on disk).
 *
 * <p>Restoring writes the header and each record back at the same place, so the file
 * Minecraft reads is the one that was backed up. Unused sectors come back as zeros rather
 * than whatever old bytes were left in them.
 */
public final class RegionLayout {

    public static final int CHUNKS = 1024;
    public static final int SECTOR = 4096;
    public static final int HEADER = 2 * SECTOR;
    static final int VERSION = 1;

    final long fileLength;
    final int[] location;
    final int[] timestamp;
    final int[] recordLength;
    final Hash[] blob;

    public RegionLayout(long fileLength, int[] location, int[] timestamp, int[] recordLength, Hash[] blob) {
        if (location.length != CHUNKS || timestamp.length != CHUNKS || recordLength.length != CHUNKS
            || blob.length != CHUNKS) {
            throw new IllegalArgumentException("a region has 1024 chunks");
        }
        this.fileLength = fileLength;
        this.location = location;
        this.timestamp = timestamp;
        this.recordLength = recordLength;
        this.blob = blob;
        for (int i = 0; i < CHUNKS; i++) {
            if (present(i) != (blob[i] != null)) {
                throw new IllegalArgumentException("chunk " + i + " has a location but no blob, or a blob but no location");
            }
        }
    }

    public long fileLength() {
        return fileLength;
    }

    /** The raw location entry: sector offset in the top 24 bits, sector count in the low 8. 0 for none. */
    public int location(int chunk) {
        return location[chunk];
    }

    public int timestamp(int chunk) {
        return timestamp[chunk];
    }

    public int recordLength(int chunk) {
        return recordLength[chunk];
    }

    /** The chunk's blob, or null if the region has no such chunk. */
    public Hash blob(int chunk) {
        return blob[chunk];
    }

    public boolean present(int chunk) {
        return location[chunk] != 0;
    }

    public static long sectorOffset(int location) {
        return (location >>> 8) & 0xFFFFFFL;
    }

    public static int sectorCount(int location) {
        return location & 0xFF;
    }

    public int chunkCount() {
        int n = 0;
        for (int i = 0; i < CHUNKS; i++) {
            if (present(i)) {
                n++;
            }
        }
        return n;
    }

    /** The 8 KiB header as Minecraft writes it: locations, then timestamps, big-endian. */
    public byte[] header() {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(HEADER);
        for (int i = 0; i < CHUNKS; i++) {
            buffer.putInt(location[i]);
        }
        for (int i = 0; i < CHUNKS; i++) {
            buffer.putInt(timestamp[i]);
        }
        return buffer.array();
    }

    byte[] encode() {
        int present = chunkCount();
        Codec.Out out = new Codec.Out(32 + present * 48);
        out.u8('R').u8(VERSION).varint(fileLength).varint(present);
        for (int i = 0; i < CHUNKS; i++) {
            if (present(i)) {
                out.varint(i).u32(location[i]).u32(timestamp[i]).varint(recordLength[i]).hash(blob[i]);
            }
        }
        // Chunks with no data but a timestamp, so the header comes back exactly.
        int stamped = 0;
        for (int i = 0; i < CHUNKS; i++) {
            if (!present(i) && timestamp[i] != 0) {
                stamped++;
            }
        }
        out.varint(stamped);
        for (int i = 0; i < CHUNKS; i++) {
            if (!present(i) && timestamp[i] != 0) {
                out.varint(i).u32(timestamp[i]);
            }
        }
        return out.toArray();
    }

    static RegionLayout decode(byte[] bytes, String what) throws CorruptException {
        Codec.In in = new Codec.In(bytes, what);
        if (in.u8() != 'R') {
            throw new CorruptException(what + " isn't a region layout");
        }
        int version = in.u8();
        if (version != VERSION) {
            throw new CorruptException(what + " is region layout version " + version + "; this xBackup reads " + VERSION);
        }
        long fileLength = in.varint();
        int[] location = new int[CHUNKS];
        int[] timestamp = new int[CHUNKS];
        int[] recordLength = new int[CHUNKS];
        Hash[] blob = new Hash[CHUNKS];
        int present = in.count(CHUNKS);
        int last = -1;
        for (int n = 0; n < present; n++) {
            int i = in.count(CHUNKS - 1);
            if (i <= last) {
                throw new CorruptException(what + " lists its chunks out of order");
            }
            last = i;
            location[i] = in.u32();
            timestamp[i] = in.u32();
            recordLength[i] = in.count(Pack.MAX_BLOB);
            blob[i] = in.hash();
            if (location[i] == 0) {
                throw new CorruptException(what + " lists chunk " + i + " with no location");
            }
        }
        int stamped = in.count(CHUNKS);
        for (int n = 0; n < stamped; n++) {
            int i = in.count(CHUNKS - 1);
            if (location[i] != 0) {
                throw new CorruptException(what + " lists chunk " + i + " twice");
            }
            timestamp[i] = in.u32();
        }
        in.expectEnd();
        return new RegionLayout(fileLength, location, timestamp, recordLength, blob);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RegionLayout r && fileLength == r.fileLength && Arrays.equals(location, r.location)
            && Arrays.equals(timestamp, r.timestamp) && Arrays.equals(recordLength, r.recordLength)
            && Arrays.equals(blob, r.blob);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(location) * 31 + Long.hashCode(fileLength);
    }
}
