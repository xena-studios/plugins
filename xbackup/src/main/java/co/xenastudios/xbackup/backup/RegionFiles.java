package co.xenastudios.xbackup.backup;

import co.xenastudios.xbackup.store.RegionLayout;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Reads Minecraft's region files ({@code .mca}): an 8 KiB header of 1,024 locations and
 * 1,024 timestamps, then chunks in 4 KiB sectors, each a 4-byte length, a compression byte
 * and the payload. Used for chunk, entity and POI regions alike.
 */
final class RegionFiles {

    private RegionFiles() {}

    /** The header: locations, then timestamps. */
    record Header(int[] location, int[] timestamp) {}

    static Header header(FileChannel channel) throws IOException {
        ByteBuffer buffer = readFully(channel, 0, RegionLayout.HEADER);
        int[] location = new int[RegionLayout.CHUNKS];
        int[] timestamp = new int[RegionLayout.CHUNKS];
        for (int i = 0; i < RegionLayout.CHUNKS; i++) {
            location[i] = buffer.getInt();
        }
        for (int i = 0; i < RegionLayout.CHUNKS; i++) {
            timestamp[i] = buffer.getInt();
        }
        return new Header(location, timestamp);
    }

    /**
     * Why the header can't be trusted, or null if every chunk's sectors are inside the file,
     * after the header, and don't overlap. A file that fails this is backed up whole.
     */
    static String problem(Header header, long fileLength) {
        long[] ranges = new long[RegionLayout.CHUNKS];
        int count = 0;
        for (int i = 0; i < RegionLayout.CHUNKS; i++) {
            int location = header.location()[i];
            if (location == 0) {
                continue;
            }
            long offset = RegionLayout.sectorOffset(location);
            int sectors = RegionLayout.sectorCount(location);
            if (offset < 2 || sectors == 0) {
                return "chunk " + i + " points into the header";
            }
            if (offset * RegionLayout.SECTOR + 5 > fileLength) {
                return "chunk " + i + " points past the end of the file";
            }
            ranges[count++] = offset << 8 | sectors;
        }
        java.util.Arrays.sort(ranges, 0, count);
        long end = 0;
        for (int i = 0; i < count; i++) {
            long offset = ranges[i] >>> 8;
            if (offset < end) {
                return "two chunks share sectors";
            }
            end = offset + (ranges[i] & 0xFF);
        }
        return null;
    }

    /**
     * One chunk's record: its 4-byte length, compression byte and payload, as on disk.
     *
     * @throws IOException if the length doesn't fit the chunk's sectors or the file
     */
    static byte[] record(FileChannel channel, int location, long fileLength) throws IOException {
        long start = RegionLayout.sectorOffset(location) * RegionLayout.SECTOR;
        int sectors = RegionLayout.sectorCount(location);
        int length = readFully(channel, start, 4).getInt();
        if (length <= 0 || length + 4L > (long) sectors * RegionLayout.SECTOR || start + 4 + length > fileLength) {
            throw new IOException("a chunk's length (" + length + ") doesn't fit its " + sectors + " sectors");
        }
        return readFully(channel, start, length + 4).array();
    }

    /** One chunk's location and timestamp, read again from the header. */
    static long entry(FileChannel channel, int chunk) throws IOException {
        int location = readFully(channel, chunk * 4L, 4).getInt();
        int timestamp = readFully(channel, RegionLayout.SECTOR + chunk * 4L, 4).getInt();
        return (long) location << 32 | timestamp & 0xFFFFFFFFL;
    }

    /** Whether the record's payload is uncompressed (compression type 3), so worth deflating. */
    static boolean uncompressed(byte[] record) {
        return record.length > 4 && (record[4] & 0x7F) == 3;
    }

    static ByteBuffer readFully(FileChannel channel, long position, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long at = position;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, at);
            if (n < 0) {
                throw new EOFException("the file ends at " + at);
            }
            at += n;
        }
        return buffer.flip();
    }
}
