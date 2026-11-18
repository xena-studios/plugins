package co.xenastudios.xbackup.store;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Pack files: blobs end to end, then a footer listing them. A pack is named by the SHA-256
 * of all its bytes and never changes once written.
 *
 * <pre>
 * blob bytes ... | footer | footer length (u32) | "XBPF"
 * footer = "XBPK" version(u8) count(varint) { type(u8) flags(u8) id(32) stored(varint) raw(varint) }...
 * </pre>
 *
 * <p>Offsets aren't stored: each blob starts where the one before it ends. The footer
 * makes every pack self-describing, so the index can always be rebuilt from the packs.
 */
public final class Pack {

    static final int VERSION = 1;
    private static final byte[] FOOTER_MAGIC = {'X', 'B', 'P', 'K'};
    private static final byte[] TRAILER_MAGIC = {'X', 'B', 'P', 'F'};
    static final int TRAILER = 8;
    /** No blob is bigger than this; a larger length in a footer means damage. */
    public static final int MAX_BLOB = 16 << 20;
    private static final int FLAG_COMPRESSED = 1;

    private Pack() {}

    /** Where packs live: {@code data/ab/ab12...}. */
    public static String fileName(Hash id) {
        String hex = id.hex();
        return "data/" + hex.substring(0, 2) + "/" + hex;
    }

    /** The pack id in a file name, or null if it isn't a pack's name. */
    public static Hash idOf(String name) {
        if (!name.startsWith("data/") || name.length() != 5 + 3 + 64) {
            return null;
        }
        String hex = name.substring(8);
        if (!Hash.isHex(hex) || !name.substring(5, 7).equals(hex.substring(0, 2))) {
            return null;
        }
        return Hash.parse(hex);
    }

    /** Writes one pack to a temporary file. Not thread-safe; {@link Session} guards it. */
    static final class Writer implements AutoCloseable {

        private final Path file;
        private final MessageDigest digest = Hash.digest();
        private final OutputStream out;
        private final List<PackEntry> entries = new ArrayList<>();
        private long size;
        private boolean done;

        Writer(Path file) throws IOException {
            this.file = file;
            FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            this.out = new DigestOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel), 1 << 16), digest);
        }

        PackEntry add(Hash id, BlobType type, byte[] stored, int rawLength, boolean compressed) throws IOException {
            out.write(stored);
            PackEntry entry = new PackEntry(id, type, compressed, size, stored.length, rawLength);
            entries.add(entry);
            size += stored.length;
            return entry;
        }

        long size() {
            return size;
        }

        int count() {
            return entries.size();
        }

        /** Writes the footer and flushes the file to disk. */
        Finished finish() throws IOException {
            Codec.Out footer = new Codec.Out(16 + entries.size() * 44);
            footer.bytes(FOOTER_MAGIC).u8(VERSION).varint(entries.size());
            for (PackEntry entry : entries) {
                footer.u8(entry.type().ordinal()).u8(entry.compressed() ? FLAG_COMPRESSED : 0).hash(entry.id())
                    .varint(entry.storedLength()).varint(entry.rawLength());
            }
            byte[] bytes = footer.toArray();
            out.write(bytes);
            out.write(new Codec.Out(8).u32(bytes.length).bytes(TRAILER_MAGIC).toArray());
            out.flush();
            done = true;
            out.close();
            LocalBackend.sync(file);
            long total = size + bytes.length + TRAILER;
            return new Finished(file, Hash.of(digest.digest()), List.copyOf(entries), total);
        }

        @Override
        public void close() throws IOException {
            if (!done) {
                done = true;
                try {
                    out.close();
                } finally {
                    Files.deleteIfExists(file);
                }
            }
        }
    }

    /** A finished pack, waiting to be stored under its id. */
    record Finished(Path file, Hash id, List<PackEntry> entries, long size) {}

    /** Reads the blob list from the end of a pack, without reading its blobs. */
    static List<PackEntry> readFooter(Backend backend, String name, long fileSize) throws IOException {
        if (fileSize < TRAILER) {
            throw new CorruptException(name + " is too short to be a pack");
        }
        Codec.In trailer = new Codec.In(backend.read(name, fileSize - TRAILER, TRAILER), name);
        int footerLength = trailer.u32();
        if (!java.util.Arrays.equals(trailer.bytes(4), TRAILER_MAGIC)) {
            throw new CorruptException(name + " doesn't end like a pack");
        }
        if (footerLength < 6 || footerLength > fileSize - TRAILER) {
            throw new CorruptException(name + " has a footer length of " + footerLength);
        }
        byte[] footer = backend.read(name, fileSize - TRAILER - footerLength, footerLength);
        return parseFooter(footer, 0, footerLength, fileSize - TRAILER - footerLength, name);
    }

    /** Reads the blob list from a whole pack in memory. */
    static List<PackEntry> parseFooter(byte[] pack, String name) throws CorruptException {
        if (pack.length < TRAILER) {
            throw new CorruptException(name + " is too short to be a pack");
        }
        Codec.In trailer = new Codec.In(pack, pack.length - TRAILER, TRAILER, name);
        int footerLength = trailer.u32();
        if (!java.util.Arrays.equals(trailer.bytes(4), TRAILER_MAGIC)) {
            throw new CorruptException(name + " doesn't end like a pack");
        }
        if (footerLength < 6 || footerLength > pack.length - TRAILER) {
            throw new CorruptException(name + " has a footer length of " + footerLength);
        }
        int start = pack.length - TRAILER - footerLength;
        return parseFooter(pack, start, footerLength, start, name);
    }

    private static List<PackEntry> parseFooter(byte[] bytes, int offset, int length, long blobsEnd, String name)
        throws CorruptException {
        Codec.In in = new Codec.In(bytes, offset, length, name + "'s footer");
        if (!java.util.Arrays.equals(in.bytes(4), FOOTER_MAGIC)) {
            throw new CorruptException(name + "'s footer has the wrong magic");
        }
        int version = in.u8();
        if (version != VERSION) {
            throw new CorruptException(name + " is pack version " + version + "; this xBackup reads " + VERSION);
        }
        int count = in.count(length / 35);
        List<PackEntry> entries = new ArrayList<>(count);
        long position = 0;
        for (int i = 0; i < count; i++) {
            BlobType type = BlobType.of(in.u8());
            int flags = in.u8();
            Hash id = in.hash();
            int stored = in.count(MAX_BLOB);
            int raw = in.count(MAX_BLOB);
            entries.add(new PackEntry(id, type, (flags & FLAG_COMPRESSED) != 0, position, stored, raw));
            position += stored;
        }
        in.expectEnd();
        if (position != blobsEnd) {
            throw new CorruptException(name + "'s footer covers " + position + " bytes of blobs, but there are " + blobsEnd);
        }
        return entries;
    }
}
