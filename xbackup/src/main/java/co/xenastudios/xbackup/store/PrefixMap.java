package co.xenastudios.xbackup.store;

import java.util.Arrays;

/**
 * Blob id prefixes (their first 8 bytes) to a small number, in open addressing over two
 * arrays: about 16 bytes a blob, for marking millions of them. Two blobs sharing a prefix
 * share an entry; {@link #putMin} keeps the smaller number, so a clash can only make a
 * blob look newer and kept longer, never deleted early.
 */
final class PrefixMap {

    private static final int EMPTY = -1;

    private long[] keys;
    private int[] values;
    private int size;

    PrefixMap() {
        keys = new long[1 << 12];
        values = new int[1 << 12];
        Arrays.fill(values, EMPTY);
    }

    /** Sets the value for the prefix to {@code value}, or keeps the old one if it's smaller. */
    void putMin(Hash id, int value) {
        if (value < 0) {
            throw new IllegalArgumentException("values are never negative");
        }
        if ((size + 1) * 2 > keys.length) {
            grow();
        }
        int i = slot(id.prefix());
        if (values[i] == EMPTY) {
            keys[i] = id.prefix();
            values[i] = value;
            size++;
        } else if (value < values[i]) {
            values[i] = value;
        }
    }

    /** The value for the blob's prefix, or -1. */
    int get(Hash id) {
        return values[slot(id.prefix())];
    }

    boolean contains(Hash id) {
        return get(id) != EMPTY;
    }

    int size() {
        return size;
    }

    private int slot(long key) {
        int mask = keys.length - 1;
        int i = (int) (key ^ (key >>> 32)) & mask;
        while (values[i] != EMPTY && keys[i] != key) {
            i = (i + 1) & mask;
        }
        return i;
    }

    private void grow() {
        long[] oldKeys = keys;
        int[] oldValues = values;
        keys = new long[oldKeys.length * 2];
        values = new int[oldKeys.length * 2];
        Arrays.fill(values, EMPTY);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldValues[i] != EMPTY) {
                int slot = slot(oldKeys[i]);
                keys[slot] = oldKeys[i];
                values[slot] = oldValues[i];
                size++;
            }
        }
    }
}
