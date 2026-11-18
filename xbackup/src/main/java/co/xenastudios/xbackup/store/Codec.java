package co.xenastudios.xbackup.store;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** The repository's binary encoding: big-endian numbers, unsigned varints, length-prefixed text. */
final class Codec {

    private Codec() {}

    /** A growable byte buffer to encode into. */
    static final class Out {

        private byte[] bytes;
        private int size;

        Out(int capacity) {
            bytes = new byte[Math.max(16, capacity)];
        }

        Out() {
            this(256);
        }

        private void room(int more) {
            if (size + more > bytes.length) {
                bytes = Arrays.copyOf(bytes, Math.max(bytes.length * 2, size + more));
            }
        }

        Out u8(int value) {
            room(1);
            bytes[size++] = (byte) value;
            return this;
        }

        Out u32(int value) {
            room(4);
            bytes[size++] = (byte) (value >>> 24);
            bytes[size++] = (byte) (value >>> 16);
            bytes[size++] = (byte) (value >>> 8);
            bytes[size++] = (byte) value;
            return this;
        }

        Out i64(long value) {
            u32((int) (value >>> 32));
            return u32((int) value);
        }

        /** An unsigned varint: 7 bits a byte, low bits first. */
        Out varint(long value) {
            if (value < 0) {
                throw new IllegalArgumentException("varints are unsigned: " + value);
            }
            room(10);
            while ((value & ~0x7FL) != 0) {
                bytes[size++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            bytes[size++] = (byte) value;
            return this;
        }

        Out hash(Hash hash) {
            room(Hash.BYTES);
            ByteBuffer.wrap(bytes, size, Hash.BYTES).putLong(hash.a).putLong(hash.b).putLong(hash.c).putLong(hash.d);
            size += Hash.BYTES;
            return this;
        }

        Out bytes(byte[] data) {
            return bytes(data, 0, data.length);
        }

        Out bytes(byte[] data, int offset, int length) {
            room(length);
            System.arraycopy(data, offset, bytes, size, length);
            size += length;
            return this;
        }

        Out text(String text) {
            byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
            varint(utf8.length);
            return bytes(utf8);
        }

        int size() {
            return size;
        }

        byte[] toArray() {
            return Arrays.copyOf(bytes, size);
        }
    }

    /** Reads what {@link Out} wrote. Running off the end is a {@link CorruptException}. */
    static final class In {

        private final byte[] bytes;
        private int pos;
        private final int end;
        private final String what;

        In(byte[] bytes, String what) {
            this(bytes, 0, bytes.length, what);
        }

        In(byte[] bytes, int offset, int length, String what) {
            this.bytes = bytes;
            this.pos = offset;
            this.end = offset + length;
            this.what = what;
        }

        private void need(int n) throws CorruptException {
            if (n < 0 || end - pos < n) {
                throw new CorruptException(what + " ends early");
            }
        }

        int u8() throws CorruptException {
            need(1);
            return bytes[pos++] & 0xFF;
        }

        int u32() throws CorruptException {
            need(4);
            int value = (bytes[pos] & 0xFF) << 24 | (bytes[pos + 1] & 0xFF) << 16
                | (bytes[pos + 2] & 0xFF) << 8 | bytes[pos + 3] & 0xFF;
            pos += 4;
            return value;
        }

        long i64() throws CorruptException {
            return (long) u32() << 32 | u32() & 0xFFFFFFFFL;
        }

        long varint() throws CorruptException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                int b = u8();
                value |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
            }
            throw new CorruptException(what + " has a number that never ends");
        }

        /** A varint that must fit an int from 0 to {@code max}. */
        int count(int max) throws CorruptException {
            long value = varint();
            if (value > max) {
                throw new CorruptException(what + " has a count of " + value + ", more than " + max);
            }
            return (int) value;
        }

        Hash hash() throws CorruptException {
            need(Hash.BYTES);
            Hash hash = Hash.read(ByteBuffer.wrap(bytes, pos, Hash.BYTES));
            pos += Hash.BYTES;
            return hash;
        }

        byte[] bytes(int length) throws CorruptException {
            need(length);
            byte[] out = Arrays.copyOfRange(bytes, pos, pos + length);
            pos += length;
            return out;
        }

        String text() throws CorruptException {
            int length = count(1 << 20);
            need(length);
            String text = new String(bytes, pos, length, StandardCharsets.UTF_8);
            pos += length;
            return text;
        }

        boolean atEnd() {
            return pos == end;
        }

        void expectEnd() throws CorruptException {
            if (pos != end) {
                throw new CorruptException(what + " has " + (end - pos) + " bytes left over");
            }
        }
    }
}
