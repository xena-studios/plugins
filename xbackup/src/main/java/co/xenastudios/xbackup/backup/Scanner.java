package co.xenastudios.xbackup.backup;

import co.xenastudios.xbackup.store.BlobType;
import co.xenastudios.xbackup.store.Chunker;
import co.xenastudios.xbackup.store.Hash;
import co.xenastudios.xbackup.store.RegionLayout;
import co.xenastudios.xbackup.store.Session;
import co.xenastudios.xbackup.store.Throttle;
import co.xenastudios.xbackup.store.Tree;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Walks folders and stores what changed: whole files cut into pieces, region files chunk
 * by chunk, SQLite databases through SQLite's own backup. Anything unchanged since the
 * {@link Reference} is reused by id without being read.
 *
 * <p>Two modes. <b>Strict</b> is the real read: trees and region layouts are stored, and
 * the result is a snapshot's tree. <b>Loose</b> is a first pass while the server is still
 * saving: it stores only content, and remembers anything it could read consistently, so the
 * strict pass during the autosave pause only has to read what changed after it.
 */
final class Scanner {

    /** A file modified this close to an earlier read may have changed again unseen. */
    static final long RACY_MILLIS = 2_000;
    private static final byte[] SQLITE_MAGIC = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    private static final Set<String> DATABASE_ENDINGS = Set.of(".db", ".sqlite", ".sqlite3", ".db3");
    private static final int MAX_WARNINGS = 20;

    private final Session session;
    private final Reference reference;
    private final boolean strict;
    private final Selection selection;
    private final Map<String, Tree.Node> ready;
    private final Throttle throttle;
    private final Databases databases;
    private final Path temp;
    private final BooleanSupplier cancelled;

    // Loose mode's findings.
    final Map<String, Tree.Node> nodes = new HashMap<>();
    final Map<Hash, RegionLayout> regions = new HashMap<>();

    long files;
    long bytes;
    long chunksRead;
    long chunksReused;
    final List<String> warnings = new ArrayList<>();

    /**
     * @param ready entries already worked out, by path, used instead of reading the path
     *     (the worlds, read during the pause)
     * @param databases how to copy databases; null to leave them out with a warning
     * @param temp where database copies go while they're stored
     */
    Scanner(Session session, Reference reference, boolean strict, Selection selection, Map<String, Tree.Node> ready,
            Throttle throttle, Databases databases, Path temp, BooleanSupplier cancelled) {
        this.session = session;
        this.reference = reference;
        this.strict = strict;
        this.selection = selection;
        this.ready = ready;
        this.throttle = throttle;
        this.databases = databases;
        this.temp = temp;
        this.cancelled = cancelled;
    }

    /** The stopped signal: the backup was cancelled. */
    static final class Cancelled extends IOException {
        Cancelled() {
            super("The backup was stopped.");
        }
    }

    private void checkCancelled() throws Cancelled {
        if (cancelled.getAsBoolean()) {
            throw new Cancelled();
        }
    }

    private void warn(String warning) {
        if (warnings.size() < MAX_WARNINGS) {
            warnings.add(warning);
        } else if (warnings.size() == MAX_WARNINGS) {
            warnings.add("and more; the console has the rest.");
        }
    }

    /** Scans the root folder; strict mode returns its entry, loose mode null. */
    Tree.Node scanRoot(Path root) throws IOException {
        return folder(root, "", "root", Files.readAttributes(root, BasicFileAttributes.class));
    }

    /** Scans one path below the root; strict mode returns its entry, loose mode null. */
    Tree.Node scan(Path file, String path) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return null;
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        return entry(file, path, name, attributes, Set.of());
    }

    private Tree.Node entry(Path file, String path, String name, BasicFileAttributes attributes, Set<String> siblings)
        throws IOException {
        Tree.Node done = ready.get(path);
        if (done != null) {
            return done.named(name);
        }
        if (attributes.isSymbolicLink()) {
            return Tree.Node.link(name, attributes.lastModifiedTime().toMillis(),
                Files.readSymbolicLink(file).toString().replace('\\', '/'));
        }
        if (attributes.isDirectory()) {
            return folder(file, path, name, attributes);
        }
        if (!attributes.isRegularFile()) {
            return null;
        }
        if (name.endsWith(".mca") && attributes.size() >= RegionLayout.HEADER) {
            return region(file, path, name, attributes);
        }
        if (isDatabase(file, name, attributes, siblings)) {
            return database(file, path, name, attributes);
        }
        return file(file, path, name, attributes);
    }

    // ---------------------------------------------------------------- folders

    private Tree.Node folder(Path dir, String path, String name, BasicFileAttributes attributes) throws IOException {
        checkCancelled();
        List<Path> children = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path child : stream) {
                children.add(child);
            }
        } catch (NoSuchFileException e) {
            return null;
        } catch (AccessDeniedException e) {
            warn("Couldn't read the folder " + display(path) + ": access denied.");
            return null;
        }
        Set<String> names = new HashSet<>();
        for (Path child : children) {
            names.add(child.getFileName().toString());
        }
        // A database's journal files are part of its copy, not files of their own.
        Set<String> skip = new HashSet<>();
        for (String child : names) {
            if (isDatabaseName(child, names)) {
                skip.add(child + "-wal");
                skip.add(child + "-shm");
                skip.add(child + "-journal");
            }
        }
        children.sort(null);
        List<Tree.Node> entries = new ArrayList<>();
        for (Path child : children) {
            String childName = child.getFileName().toString();
            String childPath = path.isEmpty() ? childName : path + "/" + childName;
            Selection.Choice choice = selection.choose(childPath, child);
            if (choice == Selection.Choice.SKIP || skip.contains(childName) && names.contains(stripJournal(childName))) {
                continue;
            }
            BasicFileAttributes childAttributes;
            try {
                childAttributes = Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                continue;
            }
            if (choice == Selection.Choice.DESCEND && !childAttributes.isDirectory() && !ready.containsKey(childPath)) {
                continue;
            }
            Tree.Node node = choice == Selection.Choice.DESCEND && childAttributes.isDirectory() && !ready.containsKey(childPath)
                ? folder(child, childPath, childName, childAttributes)
                : entry(child, childPath, childName, childAttributes, names);
            if (node != null) {
                entries.add(node);
            }
        }
        if (!strict) {
            return null;
        }
        Hash tree = session.storeTree(new Tree(entries));
        return Tree.Node.dir(name, attributes.lastModifiedTime().toMillis(), mode(dir), tree);
    }

    private static String stripJournal(String name) {
        for (String suffix : List.of("-wal", "-shm", "-journal")) {
            if (name.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    // ---------------------------------------------------------------- files

    private Tree.Node file(Path file, String path, String name, BasicFileAttributes attributes) throws IOException {
        checkCancelled();
        long size = attributes.size();
        long mtime = attributes.lastModifiedTime().toMillis();
        Reference.Previous previous = reference.find(path);
        if (previous != null && unchanged(previous, Tree.Kind.FILE, size, mtime) && stored(previous.node().blobs())) {
            Tree.Node node = previous.node().named(name);
            count(node);
            return node;
        }
        for (int attempt = 0; ; attempt++) {
            Content content;
            try {
                content = store(file);
            } catch (NoSuchFileException e) {
                return null;
            } catch (AccessDeniedException e) {
                warn("Couldn't read " + display(path) + ": access denied.");
                return null;
            }
            BasicFileAttributes after;
            try {
                after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            } catch (NoSuchFileException e) {
                return null;
            }
            boolean steady = after.size() == content.size() && after.lastModifiedTime().toMillis() == mtime;
            if (!steady && !strict) {
                // Changing as we read: the strict pass will read it again.
                return null;
            }
            if (steady || attempt >= 2) {
                if (!steady) {
                    warn(display(path) + " kept changing while it was read; its copy may be mid-write.");
                }
                Tree.Node node = Tree.Node.file(name, mtime, content.size(), mode(file), content.blobs(), content.hash());
                remember(path, node, previous);
                count(node);
                return node;
            }
            mtime = after.lastModifiedTime().toMillis();
        }
    }

    private record Content(List<Hash> blobs, long size, Hash hash) {}

    private Content store(Path file) throws IOException {
        MessageDigest digest = Hash.digest();
        List<Hash> blobs = new ArrayList<>();
        long size = 0;
        try (InputStream in = new DigestInputStream(throttle.wrap(Files.newInputStream(file)), digest)) {
            Chunker chunker = new Chunker(in);
            byte[] piece;
            while ((piece = chunker.next()) != null) {
                checkCancelled();
                blobs.add(session.store(BlobType.DATA, piece, true));
                size += piece.length;
            }
        }
        return new Content(blobs, size, Hash.of(digest.digest()));
    }

    private static boolean unchanged(Reference.Previous previous, Tree.Kind kind, long size, long mtime) {
        Tree.Node node = previous.node();
        return node.kind() == kind && node.size() == size && node.mtime() == mtime
            && mtime < previous.readAt() - RACY_MILLIS;
    }

    // Reused ids must still be in the repository: a repaired repository may have lost some.
    private boolean stored(List<Hash> blobs) {
        for (Hash blob : blobs) {
            if (!session.has(blob)) {
                return false;
            }
        }
        return true;
    }

    private void remember(String path, Tree.Node node, Reference.Previous previous) {
        if (!strict && (previous == null || !previous.node().equals(node))) {
            nodes.put(path, node);
        }
    }

    private void count(Tree.Node node) {
        files++;
        bytes += node.size();
    }

    // ---------------------------------------------------------------- regions

    private Tree.Node region(Path file, String path, String name, BasicFileAttributes attributes) throws IOException {
        checkCancelled();
        Reference.Previous previous = reference.find(path);
        RegionLayout before = previous != null && previous.node().kind() == Tree.Kind.REGION
            ? reference.region(previous.node().ref()) : null;
        for (int attempt = 0; attempt < 3; attempt++) {
            RegionLayout layout;
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
                layout = readRegion(channel, path, before);
            } catch (NoSuchFileException e) {
                return null;
            } catch (AccessDeniedException e) {
                warn("Couldn't read " + display(path) + ": access denied.");
                return null;
            }
            if (layout == UNREADABLE) {
                break;
            }
            if (layout == null) {
                // Changed under us in strict mode: read it again.
                continue;
            }
            long mtime = attributes.lastModifiedTime().toMillis();
            if (!strict) {
                if (!layout.equals(before)) {
                    Hash id = Hash.sha256(encodeForOverlay(layout));
                    regions.put(id, layout);
                    nodes.put(path, Tree.Node.region(name, mtime, layout.fileLength(), mode(file), id));
                }
                return null;
            }
            Hash id = session.storeRegion(layout);
            Tree.Node node = Tree.Node.region(name, mtime, layout.fileLength(), mode(file), id);
            count(node);
            return node;
        }
        if (!strict) {
            return null;
        }
        // A header that doesn't add up, or a file that kept changing: keep its exact bytes.
        return file(file, path, name, attributes);
    }

    // Marks a layout that can't be kept chunk by chunk.
    private static final RegionLayout UNREADABLE = new RegionLayout(0, new int[RegionLayout.CHUNKS],
        new int[RegionLayout.CHUNKS], new int[RegionLayout.CHUNKS], new Hash[RegionLayout.CHUNKS]);

    // Identifies a loose-pass layout in memory; the real id comes when it's stored.
    private static byte[] encodeForOverlay(RegionLayout layout) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(8 + RegionLayout.CHUNKS * (12 + Hash.BYTES));
        buffer.putLong(layout.fileLength());
        for (int i = 0; i < RegionLayout.CHUNKS; i++) {
            buffer.putInt(layout.location(i)).putInt(layout.timestamp(i)).putInt(layout.recordLength(i));
            buffer.put(layout.blob(i) == null ? new byte[Hash.BYTES] : layout.blob(i).bytes());
        }
        return buffer.array();
    }

    /**
     * Reads a region's layout, storing chunks that changed. Returns {@link #UNREADABLE} for a
     * file to keep whole, or null in strict mode if a chunk moved while it was read. In
     * loose mode a chunk that moved is left out (as if absent), for the strict pass to read.
     */
    private RegionLayout readRegion(FileChannel channel, String path, RegionLayout before) throws IOException {
        long fileLength = channel.size();
        if (fileLength < RegionLayout.HEADER) {
            return UNREADABLE;
        }
        RegionFiles.Header header = RegionFiles.header(channel);
        if (RegionFiles.problem(header, fileLength) != null) {
            return UNREADABLE;
        }
        int[] location = header.location().clone();
        int[] timestamp = header.timestamp().clone();
        int[] recordLength = new int[RegionLayout.CHUNKS];
        Hash[] blob = new Hash[RegionLayout.CHUNKS];
        for (int i = 0; i < RegionLayout.CHUNKS; i++) {
            if (location[i] == 0) {
                continue;
            }
            if (before != null && before.location(i) == location[i] && before.timestamp(i) == timestamp[i]
                && session.has(before.blob(i))) {
                blob[i] = before.blob(i);
                recordLength[i] = before.recordLength(i);
                chunksReused++;
                continue;
            }
            if ((i & 63) == 0) {
                checkCancelled();
            }
            byte[] record;
            try {
                record = RegionFiles.record(channel, location[i], fileLength);
            } catch (IOException e) {
                if (!strict) {
                    location[i] = 0;
                    timestamp[i] = 0;
                    continue;
                }
                return UNREADABLE;
            }
            throttle.acquire(record.length);
            // The server writes a moved chunk to new sectors before pointing the header at
            // them, and never writes into sectors the header points at. So if the entry is the
            // same after the read as before, the bytes read are that entry's.
            long entry = RegionFiles.entry(channel, i);
            if (entry != ((long) location[i] << 32 | timestamp[i] & 0xFFFFFFFFL)) {
                if (strict) {
                    return null;
                }
                location[i] = 0;
                timestamp[i] = 0;
                continue;
            }
            blob[i] = session.store(BlobType.DATA, record, RegionFiles.uncompressed(record));
            recordLength[i] = record.length;
            chunksRead++;
        }
        return new RegionLayout(fileLength, location, timestamp, recordLength, blob);
    }

    // ---------------------------------------------------------------- databases

    private static boolean isDatabaseName(String name, Set<String> siblings) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String ending : DATABASE_ENDINGS) {
            if (lower.endsWith(ending)) {
                return true;
            }
        }
        return siblings.contains(name + "-wal") || siblings.contains(name + "-journal");
    }

    private static boolean isDatabase(Path file, String name, BasicFileAttributes attributes, Set<String> siblings) {
        if (attributes.size() < 512 || !isDatabaseName(name, siblings)) {
            return false;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return Arrays.equals(in.readNBytes(SQLITE_MAGIC.length), SQLITE_MAGIC);
        } catch (IOException e) {
            return false;
        }
    }

    private Tree.Node database(Path file, String path, String name, BasicFileAttributes attributes) throws IOException {
        checkCancelled();
        long size = attributes.size();
        long mtime = attributes.lastModifiedTime().toMillis();
        Path wal = file.resolveSibling(name + "-wal");
        long walSize = -1;
        long walMtime = -1;
        try {
            BasicFileAttributes walAttributes = Files.readAttributes(wal, BasicFileAttributes.class);
            walSize = walAttributes.size();
            walMtime = walAttributes.lastModifiedTime().toMillis();
        } catch (NoSuchFileException e) {
            // Not in WAL mode, or checkpointed and closed.
        }
        Reference.Previous previous = reference.find(path);
        if (previous != null && previous.node().kind() == Tree.Kind.SQLITE && previous.node().size() == size
            && previous.node().mtime() == mtime && previous.node().walSize() == walSize
            && previous.node().walMtime() == walMtime && Math.max(mtime, walMtime) < previous.readAt() - RACY_MILLIS
            && stored(previous.node().blobs())) {
            Tree.Node node = previous.node().named(name);
            count(node);
            return node;
        }
        if (!strict) {
            // Databases are copied once, in the strict pass.
            return null;
        }
        String reason;
        if (databases == null) {
            reason = "SQLite isn't available to copy it safely";
        } else {
            Files.createDirectories(temp);
            Path copy = temp.resolve("db-" + java.util.UUID.randomUUID() + ".db");
            try {
                databases.copy(file, copy);
                Content content = store(copy);
                Tree.Node node = Tree.Node.database(name, mtime, size, mode(file), content.blobs(), content.size(),
                    content.hash(), walSize, walMtime);
                count(node);
                return node;
            } catch (Cancelled e) {
                throw e;
            } catch (IOException e) {
                reason = e.getMessage();
            } finally {
                Files.deleteIfExists(copy);
            }
        }
        // Never copy a live database's raw bytes: it could be mid-write. Keep the last good
        // copy if there is one.
        if (previous != null && previous.node().kind() == Tree.Kind.SQLITE && stored(previous.node().blobs())) {
            warn(display(path) + ": kept the previous copy, because " + reason + ".");
            Tree.Node node = previous.node().named(name);
            count(node);
            return node;
        }
        warn(display(path) + " was left out, because " + reason + ".");
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private static int mode(Path file) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file, LinkOption.NOFOLLOW_LINKS);
            int mode = 0;
            for (PosixFilePermission permission : permissions) {
                mode |= 1 << (8 - permission.ordinal());
            }
            return mode;
        } catch (UnsupportedOperationException | IOException e) {
            return -1;
        }
    }

    private static String display(String path) {
        return path.isEmpty() ? "the server folder" : path;
    }
}
