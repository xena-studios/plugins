package co.xenastudios.xbackup.store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Which pack holds each blob. On disk it's index files ({@code index/<id>}), each listing
 * the blobs of the packs one backup wrote; in memory, one table of every blob.
 *
 * <p>The table is open addressing over parallel arrays (about 65 bytes a blob), since a
 * big world has millions of chunks and a map of objects would cost several times that.
 * It's only held while a backup, restore or check runs. Safe from several threads.
 */
public final class Index {

    static final int VERSION = 1;
    private static final byte[] MAGIC = {'X', 'B', 'I', 'X'};
    private static final int FLAG_COMPRESSED = 1;

    /** Where index files live: {@code index/ab12...}. */
    public static String fileName(Hash id) {
        return "index/" + id.hex();
    }

    // ---------------------------------------------------------------- index files

    /** Encodes an index file: for each pack, the blobs in it. Deflated as a whole. */
    static byte[] encode(Map<Hash, List<PackEntry>> packs) {
        Codec.Out out = new Codec.Out(64 + packs.values().stream().mapToInt(List::size).sum() * 48);
        out.bytes(MAGIC).u8(VERSION).varint(packs.size());
        for (Map.Entry<Hash, List<PackEntry>> pack : packs.entrySet()) {
            out.hash(pack.getKey()).varint(pack.getValue().size());
            for (PackEntry entry : pack.getValue()) {
                out.u8(entry.type().ordinal()).u8(entry.compressed() ? FLAG_COMPRESSED : 0).hash(entry.id())
                    .varint(entry.offset()).varint(entry.storedLength()).varint(entry.rawLength());
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(out.size() / 2 + 64);
        try (DeflaterOutputStream deflate = new DeflaterOutputStream(bytes)) {
            deflate.write(out.toArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    /** Decodes an index file. */
    static Map<Hash, List<PackEntry>> decode(byte[] file, String name) throws CorruptException {
        byte[] raw;
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(file))) {
            raw = in.readNBytes(1 << 30);
        } catch (IOException e) {
            throw new CorruptException(name + " is damaged: " + e.getMessage(), e);
        }
        Codec.In in = new Codec.In(raw, name);
        if (!Arrays.equals(in.bytes(4), MAGIC)) {
            throw new CorruptException(name + " isn't an index file");
        }
        int version = in.u8();
        if (version != VERSION) {
            throw new CorruptException(name + " is index version " + version + "; this xBackup reads " + VERSION);
        }
        int packCount = in.count(raw.length / 33);
        Map<Hash, List<PackEntry>> packs = new LinkedHashMap<>();
        for (int p = 0; p < packCount; p++) {
            Hash pack = in.hash();
            int count = in.count(raw.length / 36);
            List<PackEntry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                BlobType type = BlobType.of(in.u8());
                int flags = in.u8();
                Hash id = in.hash();
                long offset = in.varint();
                int stored = in.count(Pack.MAX_BLOB);
                int rawLength = in.count(Pack.MAX_BLOB);
                entries.add(new PackEntry(id, type, (flags & FLAG_COMPRESSED) != 0, offset, stored, rawLength));
            }
            packs.put(pack, entries);
        }
        in.expectEnd();
        return packs;
    }

    // ---------------------------------------------------------------- the table

    /** A blob's place: its pack and where in it. */
    public record Location(Hash pack, PackEntry entry) {}

    private final List<Hash> packIds = new ArrayList<>();
    private final Map<Hash, Integer> packNumbers = new HashMap<>();
    private long[] k0;
    private long[] k1;
    private long[] k2;
    private long[] k3;
    private int[] pack;
    private int[] offset;
    private int[] stored;
    private int[] raw;
    private byte[] kind;
    private int size;
    private int mask;

    public Index() {
        allocate(1 << 10);
    }

    private void allocate(int capacity) {
        k0 = new long[capacity];
        k1 = new long[capacity];
        k2 = new long[capacity];
        k3 = new long[capacity];
        pack = new int[capacity];
        Arrays.fill(pack, -1);
        offset = new int[capacity];
        stored = new int[capacity];
        raw = new int[capacity];
        kind = new byte[capacity];
        mask = capacity - 1;
    }

    /** Adds every blob of a pack. A blob already listed keeps its first place. */
    public synchronized void add(Hash packId, List<PackEntry> entries) {
        Integer number = packNumbers.get(packId);
        if (number == null) {
            number = packIds.size();
            packIds.add(packId);
            packNumbers.put(packId, number);
        }
        for (PackEntry entry : entries) {
            if (entry.offset() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("pack " + packId + " is too big");
            }
            put(entry.id(), number, (int) entry.offset(), entry.storedLength(), entry.rawLength(),
                (byte) (entry.type().ordinal() << 1 | (entry.compressed() ? 1 : 0)));
        }
    }

    private void put(Hash id, int packNumber, int off, int storedLength, int rawLength, byte flags) {
        if ((size + 1) * 4L > (mask + 1) * 3L) {
            grow();
        }
        int slot = slot(id);
        if (pack[slot] >= 0) {
            return;
        }
        k0[slot] = id.a;
        k1[slot] = id.b;
        k2[slot] = id.c;
        k3[slot] = id.d;
        pack[slot] = packNumber;
        offset[slot] = off;
        stored[slot] = storedLength;
        raw[slot] = rawLength;
        kind[slot] = flags;
        size++;
    }

    private void grow() {
        long[] o0 = k0;
        long[] o1 = k1;
        long[] o2 = k2;
        long[] o3 = k3;
        int[] oPack = pack;
        int[] oOffset = offset;
        int[] oStored = stored;
        int[] oRaw = raw;
        byte[] oKind = kind;
        allocate((mask + 1) * 2);
        size = 0;
        for (int i = 0; i < oPack.length; i++) {
            if (oPack[i] >= 0) {
                put(new Hash(o0[i], o1[i], o2[i], o3[i]), oPack[i], oOffset[i], oStored[i], oRaw[i], oKind[i]);
            }
        }
    }

    // The slot holding the id, or the empty slot where it would go.
    private int slot(Hash id) {
        int i = (int) (id.a ^ (id.a >>> 29) ^ id.b) & mask;
        while (pack[i] >= 0 && !(k0[i] == id.a && k1[i] == id.b && k2[i] == id.c && k3[i] == id.d)) {
            i = (i + 1) & mask;
        }
        return i;
    }

    public synchronized boolean has(Hash id) {
        return pack[slot(id)] >= 0;
    }

    /** Where the blob is, or null if no pack has it. */
    public synchronized Location find(Hash id) {
        int i = slot(id);
        if (pack[i] < 0) {
            return null;
        }
        return new Location(packIds.get(pack[i]), entryAt(i));
    }

    private PackEntry entryAt(int i) {
        return new PackEntry(new Hash(k0[i], k1[i], k2[i], k3[i]), BlobType.values()[kind[i] >> 1], (kind[i] & 1) != 0,
            offset[i] & 0xFFFFFFFFL, stored[i], raw[i]);
    }

    public synchronized int size() {
        return size;
    }

    /** Every pack the index lists, including any whose blobs are all listed elsewhere too. */
    public synchronized List<Hash> packs() {
        return List.copyOf(packIds);
    }

    /** Calls back with every blob and its pack. Don't change the index meanwhile. */
    public synchronized void forEach(Consumer<Location> action) {
        for (int i = 0; i < pack.length; i++) {
            if (pack[i] >= 0) {
                action.accept(new Location(packIds.get(pack[i]), entryAt(i)));
            }
        }
    }

    /** The blobs grouped by pack, for writing a fresh index file. */
    public synchronized Map<Hash, List<PackEntry>> byPack() {
        Map<Hash, List<PackEntry>> packs = new LinkedHashMap<>();
        for (int i = 0; i < pack.length; i++) {
            if (pack[i] >= 0) {
                packs.computeIfAbsent(packIds.get(pack[i]), key -> new ArrayList<>()).add(entryAt(i));
            }
        }
        return packs;
    }
}
