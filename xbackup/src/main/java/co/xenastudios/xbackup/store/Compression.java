package co.xenastudios.xbackup.store;

import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Blob compression: the JDK's deflate, in zlib format so each compressed blob also carries
 * its own checksum. A blob is kept as it is when deflate doesn't make it smaller, as with
 * region chunks, which Minecraft has already compressed.
 */
public final class Compression {

    private Compression() {}

    private static final ThreadLocal<Deflater> DEFLATERS = ThreadLocal.withInitial(() -> new Deflater(6));
    private static final ThreadLocal<Inflater> INFLATERS = ThreadLocal.withInitial(Inflater::new);

    /**
     * The bytes deflated, or null if that wouldn't save at least 1/32 of them.
     *
     * @param level 1 (fastest) to 9 (smallest)
     */
    public static byte[] deflate(byte[] raw, int offset, int length, int level) {
        if (length < 64) {
            return null;
        }
        Deflater deflater = DEFLATERS.get();
        deflater.reset();
        deflater.setLevel(level);
        deflater.setInput(raw, offset, length);
        deflater.finish();
        int limit = length - length / 32;
        byte[] out = new byte[limit];
        int size = 0;
        while (!deflater.finished()) {
            if (size == out.length) {
                return null;
            }
            size += deflater.deflate(out, size, out.length - size);
        }
        return Arrays.copyOf(out, size);
    }

    /** Inflates a blob back to exactly {@code rawLength} bytes. */
    public static byte[] inflate(byte[] stored, int rawLength) throws CorruptException {
        Inflater inflater = INFLATERS.get();
        inflater.reset();
        inflater.setInput(stored);
        byte[] out = new byte[rawLength];
        try {
            int size = 0;
            while (size < rawLength) {
                int n = inflater.inflate(out, size, rawLength - size);
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                size += n;
            }
            if (size != rawLength || !inflater.finished()) {
                throw new CorruptException("a compressed blob doesn't inflate to its recorded size");
            }
        } catch (DataFormatException e) {
            throw new CorruptException("a compressed blob is damaged: " + e.getMessage(), e);
        }
        return out;
    }
}
