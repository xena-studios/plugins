package co.xenastudios.xbackup.store;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Splits a file into pieces at content-defined points (FastCDC's gear hash with normalised
 * chunking), so an insertion near the start of a big file moves only the piece it's in:
 * the rest cut at the same places as before, hash the same, and are stored once.
 *
 * <p>Pieces are 128 KiB to 2 MiB, about 512 KiB on average. A file smaller than the
 * minimum is one piece. The gear table and masks are part of the repository format:
 * changing them would stop new pieces matching old ones.
 */
public final class Chunker {

    public static final int MIN = 128 << 10;
    public static final int AVERAGE = 512 << 10;
    public static final int MAX = 2 << 20;

    private static final long[] GEAR = new long[256];
    // 21 one-bits before the average size (cuts less likely), 17 after (more likely).
    private static final long MASK_SMALL = mask(21);
    private static final long MASK_LARGE = mask(17);

    static {
        // SplitMix64 from a fixed seed: the same table on every JVM, forever.
        long state = 0x7842_6163_6B75_7031L;
        for (int i = 0; i < GEAR.length; i++) {
            state += 0x9E3779B97F4A7C15L;
            long z = state;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            GEAR[i] = z ^ (z >>> 31);
        }
    }

    private static long mask(int bits) {
        return ((1L << bits) - 1) << (64 - bits);
    }

    private final InputStream in;
    private final byte[] buffer = new byte[MAX];
    private int length;
    private boolean eof;

    public Chunker(InputStream in) {
        this.in = in;
    }

    /** The next piece, or null at the end of the stream. */
    public byte[] next() throws IOException {
        while (!eof && length < MAX) {
            int n = in.read(buffer, length, MAX - length);
            if (n < 0) {
                eof = true;
            } else {
                length += n;
            }
        }
        if (length == 0) {
            return null;
        }
        int cut = cut(buffer, length);
        byte[] piece = Arrays.copyOf(buffer, cut);
        System.arraycopy(buffer, cut, buffer, 0, length - cut);
        length -= cut;
        return piece;
    }

    /** Where the first piece of {@code data[0..length)} ends. */
    static int cut(byte[] data, int length) {
        if (length <= MIN) {
            return length;
        }
        int end = Math.min(length, MAX);
        int normal = Math.min(end, AVERAGE);
        long fingerprint = 0;
        int i = MIN;
        for (; i < normal; i++) {
            fingerprint = (fingerprint << 1) + GEAR[data[i] & 0xFF];
            if ((fingerprint & MASK_SMALL) == 0) {
                return i + 1;
            }
        }
        for (; i < end; i++) {
            fingerprint = (fingerprint << 1) + GEAR[data[i] & 0xFF];
            if ((fingerprint & MASK_LARGE) == 0) {
                return i + 1;
            }
        }
        return end;
    }
}
