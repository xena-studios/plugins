package co.xenastudios.xbackup.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One folder of a snapshot: its entries, sorted by name. Stored as a {@link BlobType#TREE}
 * blob, so a folder that didn't change between backups is the same blob and stored once.
 */
public record Tree(List<Node> nodes) {

    static final int VERSION = 1;
    private static final int MAX_NODES = 1 << 22;

    public Tree {
        List<Node> sorted = new ArrayList<>(nodes);
        sorted.sort(Comparator.comparing(Node::name));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).name().equals(sorted.get(i - 1).name())) {
                throw new IllegalArgumentException("two entries named " + sorted.get(i).name());
            }
        }
        nodes = Collections.unmodifiableList(sorted);
    }

    public static final Tree EMPTY = new Tree(List.of());

    /** The entry with this name, or null. */
    public Node get(String name) {
        int low = 0;
        int high = nodes.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int c = nodes.get(mid).name().compareTo(name);
            if (c == 0) {
                return nodes.get(mid);
            } else if (c < 0) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return null;
    }

    /** The entries by name. */
    public Map<String, Node> byName() {
        Map<String, Node> map = new HashMap<>(nodes.size() * 2);
        for (Node node : nodes) {
            map.put(node.name(), node);
        }
        return map;
    }

    /** What an entry is. */
    public enum Kind {
        /** A file, stored as one or more data blobs. */
        FILE,
        /** A folder: {@link Node#ref} is its tree. */
        DIR,
        /** A region file: {@link Node#ref} is its {@link RegionLayout}. */
        REGION,
        /** A SQLite database, copied with SQLite's own backup; the blobs hold that copy. */
        SQLITE,
        /** A symbolic link: {@link Node#target}. */
        LINK
    }

    /**
     * One entry.
     *
     * @param mtime last modified, in milliseconds since 1970
     * @param size the file's size on disk when it was read
     * @param mode POSIX permission bits, or -1 if the system has none
     * @param blobs the content, in order (files and databases)
     * @param contentSize the content's length: the file's size, or the database copy's
     * @param contentHash the SHA-256 of the whole content, checked after a restore
     * @param ref the tree (folders) or layout (region files)
     * @param target where a link points
     * @param walSize a database's write-ahead log size when read, or -1
     * @param walMtime a database's write-ahead log's last modified time, or -1
     */
    public record Node(String name, Kind kind, long mtime, long size, int mode, List<Hash> blobs, long contentSize,
                       Hash contentHash, Hash ref, String target, long walSize, long walMtime) {

        public Node {
            Objects.requireNonNull(name);
            Objects.requireNonNull(kind);
            if (name.isEmpty() || name.contains("/") || name.equals(".") || name.equals("..") || name.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("not a file name: " + name);
            }
            blobs = blobs == null ? List.of() : List.copyOf(blobs);
            switch (kind) {
                case FILE, SQLITE -> Objects.requireNonNull(contentHash, "content hash");
                case DIR, REGION -> Objects.requireNonNull(ref, "ref");
                case LINK -> Objects.requireNonNull(target, "target");
            }
        }

        public static Node file(String name, long mtime, long size, int mode, List<Hash> blobs, Hash contentHash) {
            return new Node(name, Kind.FILE, mtime, size, mode, blobs, size, contentHash, null, null, -1, -1);
        }

        public static Node database(String name, long mtime, long size, int mode, List<Hash> blobs, long contentSize,
                                    Hash contentHash, long walSize, long walMtime) {
            return new Node(name, Kind.SQLITE, mtime, size, mode, blobs, contentSize, contentHash, null, null,
                walSize, walMtime);
        }

        public static Node dir(String name, long mtime, int mode, Hash tree) {
            return new Node(name, Kind.DIR, mtime, 0, mode, List.of(), 0, null, tree, null, -1, -1);
        }

        public static Node region(String name, long mtime, long size, int mode, Hash layout) {
            return new Node(name, Kind.REGION, mtime, size, mode, List.of(), size, null, layout, null, -1, -1);
        }

        public static Node link(String name, long mtime, String target) {
            return new Node(name, Kind.LINK, mtime, 0, -1, List.of(), 0, null, null, target, -1, -1);
        }

        /** The same entry under another name. */
        public Node named(String other) {
            return new Node(other, kind, mtime, size, mode, blobs, contentSize, contentHash, ref, target, walSize, walMtime);
        }
    }

    // ---------------------------------------------------------------- encoding

    byte[] encode() {
        Codec.Out out = new Codec.Out(32 + nodes.size() * 96);
        out.u8('T').u8(VERSION).varint(nodes.size());
        for (Node node : nodes) {
            out.text(node.name()).u8(node.kind().ordinal()).i64(node.mtime()).varint(node.size()).u32(node.mode());
            switch (node.kind()) {
                case FILE, SQLITE -> {
                    out.varint(node.contentSize()).hash(node.contentHash()).varint(node.blobs().size());
                    for (Hash blob : node.blobs()) {
                        out.hash(blob);
                    }
                    if (node.kind() == Kind.SQLITE) {
                        out.i64(node.walSize()).i64(node.walMtime());
                    }
                }
                case DIR, REGION -> out.hash(node.ref());
                case LINK -> out.text(node.target());
            }
        }
        return out.toArray();
    }

    static Tree decode(byte[] bytes, String what) throws CorruptException {
        Codec.In in = new Codec.In(bytes, what);
        if (in.u8() != 'T') {
            throw new CorruptException(what + " isn't a tree");
        }
        int version = in.u8();
        if (version != VERSION) {
            throw new CorruptException(what + " is tree version " + version + "; this xBackup reads " + VERSION);
        }
        int count = in.count(MAX_NODES);
        List<Node> nodes = new ArrayList<>(Math.min(count, 4096));
        Kind[] kinds = Kind.values();
        for (int i = 0; i < count; i++) {
            String name = in.text();
            int k = in.u8();
            if (k >= kinds.length) {
                throw new CorruptException(what + " has an entry of unknown kind " + k);
            }
            Kind kind = kinds[k];
            long mtime = in.i64();
            long size = in.varint();
            int mode = in.u32();
            try {
                nodes.add(switch (kind) {
                    case FILE, SQLITE -> {
                        long contentSize = in.varint();
                        Hash contentHash = in.hash();
                        int blobCount = in.count(1 << 24);
                        List<Hash> blobs = new ArrayList<>(Math.min(blobCount, 4096));
                        for (int b = 0; b < blobCount; b++) {
                            blobs.add(in.hash());
                        }
                        long walSize = -1;
                        long walMtime = -1;
                        if (kind == Kind.SQLITE) {
                            walSize = in.i64();
                            walMtime = in.i64();
                        }
                        yield new Node(name, kind, mtime, size, mode, blobs, contentSize, contentHash, null, null,
                            walSize, walMtime);
                    }
                    case DIR, REGION -> new Node(name, kind, mtime, size, mode, List.of(), size, null, in.hash(), null, -1, -1);
                    case LINK -> new Node(name, kind, mtime, size, mode, List.of(), 0, null, null, in.text(), -1, -1);
                });
            } catch (IllegalArgumentException e) {
                throw new CorruptException(what + ": " + e.getMessage(), e);
            }
        }
        in.expectEnd();
        try {
            return new Tree(nodes);
        } catch (IllegalArgumentException e) {
            throw new CorruptException(what + ": " + e.getMessage(), e);
        }
    }
}
