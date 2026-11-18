package co.xenastudios.xbackup.restore;

import co.xenastudios.xbackup.store.CorruptException;
import co.xenastudios.xbackup.store.Hash;
import co.xenastudios.xbackup.store.RegionLayout;
import co.xenastudios.xbackup.store.Repository;
import co.xenastudios.xbackup.store.Snapshot;
import co.xenastudios.xbackup.store.Throttle;
import co.xenastudios.xbackup.store.Tree;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Getting files back out of a snapshot: finding a path in it, saying what restoring it
 * would change, and writing it out to a folder with every file checked against the hash it
 * was saved with. Pure Java: putting the result in place is {@link Swap}'s job.
 */
public final class Restore {

    private Restore() {}

    /** A path in a snapshot and its entry. "" is the whole snapshot. */
    public record Item(String path, Tree.Node node) {}

    /**
     * Finds a path in a snapshot.
     *
     * @return its entry, or null if the snapshot doesn't have it
     */
    public static Tree.Node find(Repository repository, Snapshot snapshot, String path) throws IOException {
        Tree.Node node = Tree.Node.dir("root", 0, -1, snapshot.tree());
        if (path.isEmpty()) {
            return node;
        }
        for (String part : path.split("/")) {
            if (node.kind() != Tree.Kind.DIR) {
                return null;
            }
            node = repository.tree(node.ref()).get(part);
            if (node == null) {
                return null;
            }
        }
        return node;
    }

    /** Normalises a path as typed: {@code ./world/} becomes {@code world}. */
    public static String normalise(String path) {
        String clean = path.strip().replace('\\', '/');
        while (clean.startsWith("./")) {
            clean = clean.substring(2);
        }
        while (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        if (clean.equals(".")) {
            return "";
        }
        for (String part : clean.split("/")) {
            if (part.equals("..")) {
                throw new IllegalArgumentException("A restore path can't go up with \"..\".");
            }
        }
        return clean;
    }

    /**
     * Splits the paths into what can be swapped in whole: a path that contains a protected one
     * (xBackup's own folder, its jar, the repository) is replaced by its children, and the
     * protected paths themselves are left out.
     */
    public static List<Item> expand(Repository repository, Snapshot snapshot, List<String> paths, Set<String> protect)
        throws IOException {
        List<Item> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String path : paths) {
            Tree.Node node = find(repository, snapshot, path);
            if (node == null) {
                throw new NoSuchFileException("Snapshot " + snapshot.id().shortHex() + " has no " + path + ".");
            }
            expand(repository, path, node, protect, items, seen);
        }
        return items;
    }

    private static void expand(Repository repository, String path, Tree.Node node, Set<String> protect, List<Item> items,
                               Set<String> seen) throws IOException {
        // Compared ignoring case: on Windows and macOS, Plugins/XBACKUP is plugins/xBackup.
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        boolean containsProtected = path.isEmpty();
        for (String p : protect) {
            String protectedPath = p.toLowerCase(java.util.Locale.ROOT);
            if (protectedPath.equals(lower)) {
                return;
            }
            if (protectedPath.startsWith(lower + "/")) {
                containsProtected = true;
            }
        }
        if (!containsProtected || node.kind() != Tree.Kind.DIR) {
            if (seen.add(path)) {
                items.add(new Item(path, node));
            }
            return;
        }
        for (Tree.Node child : repository.tree(node.ref()).nodes()) {
            expand(repository, path.isEmpty() ? child.name() : path + "/" + child.name(), child, protect, items, seen);
        }
    }

    // ---------------------------------------------------------------- dry run

    /**
     * What a restore would change, compared with what's on disk now.
     *
     * @param changedChunks region chunks that differ, across all region files
     * @param examples a few of the changed paths
     */
    public record Plan(List<Item> items, long files, long bytes, int added, int changed, int removed, long changedChunks,
                       List<String> examples) {}

    /** Compares the items with the files under {@code root}, reading only metadata and region headers. */
    public static Plan dryRun(Repository repository, List<Item> items, Path root) throws IOException {
        Counter counter = new Counter();
        Path base = root.toAbsolutePath().normalize();
        for (Item item : items) {
            Path disk = base.resolve(item.path()).normalize();
            if (!disk.startsWith(base)) {
                throw new IOException(item.path() + " is outside " + base + ".");
            }
            if (item.node().kind() == Tree.Kind.DIR && Files.isSymbolicLink(disk) && Files.isDirectory(disk)) {
                // A folder kept elsewhere behind a link is restored where the link leads.
                disk = disk.toRealPath();
            }
            compare(repository, item.path(), item.node(), disk, disk, counter);
        }
        return new Plan(items, counter.files, counter.bytes, counter.added, counter.changed, counter.removed,
            counter.chunks, List.copyOf(counter.examples));
    }

    private static final class Counter {
        long files;
        long bytes;
        int added;
        int changed;
        int removed;
        long chunks;
        final List<String> examples = new ArrayList<>();

        void example(String text) {
            if (examples.size() < 6) {
                examples.add(text);
            }
        }
    }

    // Everything read stays inside the item's own folder (or file): names come from the backup.
    private static void compare(Repository repository, String path, Tree.Node node, Path base, Path at, Counter counter)
        throws IOException {
        Path disk = at.normalize();
        if (!disk.startsWith(base)) {
            throw new IOException(path + " is outside " + base + ".");
        }
        BasicFileAttributes attributes = attributes(disk);
        if (node.kind() == Tree.Kind.DIR) {
            Tree tree = repository.tree(node.ref());
            if (attributes == null || !attributes.isDirectory()) {
                counter.added += countFiles(repository, tree, counter);
                counter.example("+ " + path + "/ (comes back)");
                return;
            }
            Set<String> names = new HashSet<>();
            for (Tree.Node child : tree.nodes()) {
                names.add(child.name());
                compare(repository, join(path, child.name()), child, base, disk.resolve(child.name()), counter);
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(disk)) {
                for (Path child : stream) {
                    String name = child.getFileName().toString();
                    // The server makes a new session.lock; it's never backed up.
                    if (!names.contains(name) && !name.equals("session.lock")) {
                        counter.removed++;
                        counter.example("- " + join(path, name) + " (set aside)");
                    }
                }
            }
            return;
        }
        counter.files++;
        counter.bytes += node.contentSize();
        if (attributes == null) {
            counter.added++;
            counter.example("+ " + path);
            return;
        }
        if (node.kind() == Tree.Kind.REGION && attributes.isRegularFile()) {
            int differ = differingChunks(repository.region(node.ref()), disk);
            if (differ != 0) {
                counter.changed++;
                counter.chunks += Math.max(differ, 0);
                counter.example("~ " + path + (differ > 0 ? " (" + differ + " chunks)" : ""));
            }
            return;
        }
        if (node.kind() == Tree.Kind.LINK || attributes.size() != node.size()
            || attributes.lastModifiedTime().toMillis() != node.mtime() && !sameContent(node, disk, attributes)) {
            counter.changed++;
            counter.example("~ " + path);
        }
    }

    // A file the server rewrote without changing (its time moved, its bytes didn't). Only
    // small files are hashed, to keep a dry run quick.
    private static boolean sameContent(Tree.Node node, Path disk, BasicFileAttributes attributes) {
        if (node.kind() != Tree.Kind.FILE || attributes.size() > (8 << 20)) {
            return false;
        }
        try {
            return Hash.sha256(Files.readAllBytes(disk)).equals(node.contentHash());
        } catch (IOException e) {
            return false;
        }
    }

    private static int countFiles(Repository repository, Tree tree, Counter counter) throws IOException {
        int n = 0;
        for (Tree.Node child : tree.nodes()) {
            if (child.kind() == Tree.Kind.DIR) {
                n += countFiles(repository, repository.tree(child.ref()), counter);
            } else {
                n++;
                counter.files++;
                counter.bytes += child.contentSize();
            }
        }
        return n;
    }

    // Chunks whose place or timestamp differ from the layout; -1 if the file can't be read as a region.
    private static int differingChunks(RegionLayout layout, Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (channel.size() < RegionLayout.HEADER) {
                return -1;
            }
            ByteBuffer header = ByteBuffer.allocate(RegionLayout.HEADER);
            while (header.hasRemaining() && channel.read(header, header.position()) > 0) {
                // Keep reading.
            }
            header.flip();
            int differ = 0;
            for (int i = 0; i < RegionLayout.CHUNKS; i++) {
                int location = header.getInt(i * 4);
                int timestamp = header.getInt(RegionLayout.SECTOR + i * 4);
                if (location != layout.location(i) || timestamp != layout.timestamp(i)) {
                    differ++;
                }
            }
            return differ;
        } catch (IOException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- writing

    /** What {@link #write} wrote. */
    public record Written(long files, long bytes, long chunks) {}

    /**
     * Writes the items under {@code target} (each at its own path), checking every file against
     * the hash it was saved with and every region chunk against its blob's. Nothing under
     * {@code target} should exist yet. The files will stay where they are written: links in
     * the backup may only point inside {@code target}.
     */
    public static Written write(Repository repository, List<Item> items, Path target, Throttle throttle,
                                BooleanSupplier cancelled, Consumer<String> progress) throws IOException {
        return write(repository, items, target, target, false, throttle, cancelled, progress);
    }

    /**
     * Writes the items under {@code target}, as {@link #write(Repository, List, Path, Throttle,
     * BooleanSupplier, Consumer)} does, for files that will be moved to {@code home} afterwards.
     *
     * <p>A repository or remote can't make a restore write anywhere else: every name is
     * checked before it's used, every path is checked to stay inside {@code target}, nothing
     * is written through a link, and a link that would point outside {@code home} is refused
     * (unless {@code allowLinksOutside}, for owners who keep links to other disks on purpose).
     *
     * @param home where the restored files will end up, which links are checked against
     */
    public static Written write(Repository repository, List<Item> items, Path target, Path home,
                                boolean allowLinksOutside, Throttle throttle, BooleanSupplier cancelled,
                                Consumer<String> progress) throws IOException {
        Path base = target.toAbsolutePath().normalize();
        Writer writer = new Writer(repository, base, home.toAbsolutePath().normalize(), allowLinksOutside, throttle,
            cancelled, progress);
        Files.createDirectories(base);
        for (Item item : items) {
            Path out = item.path().isEmpty() ? base : writer.inside(base.resolve(item.path()), item.path());
            if (!item.path().isEmpty()) {
                writer.makeParents(base, item.path());
            }
            writer.write(item.node(), out, item.path());
        }
        writer.finishFolders();
        return new Written(writer.files, writer.bytes, writer.chunks);
    }

    /** A restore that would write outside its folder, or through a link: refused. */
    public static final class UnsafeException extends IOException {
        UnsafeException(String message) {
            super(message);
        }
    }

    private static final boolean WINDOWS = java.io.File.separatorChar == '\\';

    /**
     * Throws unless the name is one plain file name on this system: no control characters,
     * and on Windows no {@code \\} or {@code :} (a folder separator, a drive or a stream).
     * Tree entries already can't hold {@code /}, {@code .} or {@code ..}.
     */
    static void checkName(String name, String path, boolean windows) throws UnsafeException {
        boolean plain = true;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7F || windows && (c == '\\' || c == ':' || "<>\"|?*".indexOf(c) >= 0)) {
                plain = false;
            }
        }
        // Windows also drops a trailing dot or space (so "world." is "world"), keeps device
        // names (NUL, COM1) whatever their extension, and reads PROGRA~1 as a short name for
        // some other, longer one: any of them could write somewhere else than its name says.
        if (plain && windows && (name.endsWith(".") || name.endsWith(" ") || RESERVED.matcher(name).matches()
            || SHORT_NAME.matcher(name).find())) {
            plain = false;
        }
        if (!plain) {
            throw new UnsafeException("The backup has an entry named " + printable(name) + " in " + display(path)
                + ", which isn't a plain file name here. It wasn't restored; the backup may have been tampered with.");
        }
    }

    private static final java.util.regex.Pattern RESERVED = java.util.regex.Pattern.compile(
        "(?i)(con|prn|aux|nul|conin\\$|conout\\$|com[0-9\u00b9\u00b2\u00b3]|lpt[0-9\u00b9\u00b2\u00b3])( *\\..*)?");
    private static final java.util.regex.Pattern SHORT_NAME = java.util.regex.Pattern.compile("~[0-9]");

    private static String printable(String name) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : name.toCharArray()) {
            out.append(c < 0x20 || c == 0x7F ? String.format("\\u%04x", (int) c) : String.valueOf(c));
        }
        return out.append('"').toString();
    }

    private static String display(String path) {
        return path.isEmpty() ? "the top folder" : path;
    }

    private static final class Writer {
        private final Repository repository;
        private final Path base;
        private final Path home;
        private final boolean allowLinksOutside;
        private final Throttle throttle;
        private final BooleanSupplier cancelled;
        private final Consumer<String> progress;
        private final List<Object[]> folders = new ArrayList<>();
        long files;
        long bytes;
        long chunks;

        Writer(Repository repository, Path base, Path home, boolean allowLinksOutside, Throttle throttle,
               BooleanSupplier cancelled, Consumer<String> progress) {
            this.repository = repository;
            this.base = base;
            this.home = home;
            this.allowLinksOutside = allowLinksOutside;
            this.throttle = throttle;
            this.cancelled = cancelled;
            this.progress = progress;
        }

        // The path, if it's inside the folder being written; throws if it isn't.
        Path inside(Path out, String path) throws UnsafeException {
            Path clean = out.toAbsolutePath().normalize();
            if (!clean.startsWith(base) || clean.equals(base) && !path.isEmpty()) {
                throw new UnsafeException(display(path) + " would be written outside " + base + ". Nothing more was"
                    + " written; the backup may have been tampered with.");
            }
            return clean;
        }

        // The folders above an item, made one by one, none of them a link.
        void makeParents(Path base, String path) throws IOException {
            String[] parts = path.split("/");
            Path at = base;
            StringBuilder sofar = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                checkName(parts[i], sofar.toString(), WINDOWS);
                at = inside(at.resolve(parts[i]), join(sofar.toString(), parts[i]));
                sofar.append(sofar.isEmpty() ? "" : "/").append(parts[i]);
                makeFolder(at, sofar.toString());
            }
            checkName(parts[parts.length - 1], sofar.toString(), WINDOWS);
        }

        // A new folder, or one this restore already made; never a link to somewhere else.
        private void makeFolder(Path out, String path) throws IOException {
            try {
                Files.createDirectory(out);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                BasicFileAttributes attributes = attributes(out);
                if (attributes == null || !attributes.isDirectory()) {
                    throw new UnsafeException(display(path) + " is already there as "
                        + (attributes != null && attributes.isSymbolicLink() ? "a link" : "a file")
                        + ", so nothing is written into it. Two entries in the backup may have names that differ only in"
                        + " case, which this disk can't keep apart.");
                }
            }
        }

        void write(Tree.Node node, Path out, String path) throws IOException {
            if (cancelled.getAsBoolean()) {
                throw new IOException("The restore was stopped.");
            }
            switch (node.kind()) {
                case DIR -> {
                    if (out.equals(base)) {
                        Files.createDirectories(out);
                    } else {
                        makeFolder(out, path);
                    }
                    for (Tree.Node child : repository.tree(node.ref()).nodes()) {
                        checkName(child.name(), path, WINDOWS);
                        String childPath = join(path, child.name());
                        write(child, inside(out.resolve(child.name()), childPath), childPath);
                    }
                    folders.add(new Object[] {out, node});
                }
                case FILE, SQLITE -> {
                    writeFile(node, out, path);
                    finish(out, node);
                }
                case REGION -> {
                    writeRegion(repository.region(node.ref()), out, path);
                    finish(out, node);
                }
                case LINK -> writeLink(node, out, path);
            }
        }

        // A link is put back only if it points inside the folder the files end up in.
        private void writeLink(Tree.Node node, Path out, String path) throws IOException {
            String target = node.target();
            if (target.isEmpty() || target.indexOf('\0') >= 0) {
                throw new UnsafeException(display(path) + " is a link with no usable target in the backup.");
            }
            Path link;
            try {
                link = Path.of(WINDOWS ? target : target.replace('\\', '/'));
            } catch (java.nio.file.InvalidPathException e) {
                throw new UnsafeException(display(path) + " is a link to " + printable(target) + ", which isn't a path here.");
            }
            Path placed = home.resolve(base.relativize(out).toString());
            Path leads = (link.isAbsolute() ? link : placed.getParent().resolve(link)).normalize();
            if (!allowLinksOutside && !(leads.startsWith(home) && resolved(leads).startsWith(resolved(home)))) {
                throw new UnsafeException(display(path) + " is a link to " + target + ", outside " + home
                    + ", so it isn't restored. If you made that link yourself, restore it from the command line with"
                    + " --allow-links-outside, or leave it out of the restore.");
            }
            Files.createSymbolicLink(out, link);
        }

        // Where a path really leads through the links already on disk: its longest existing
        // part resolved, the rest added back. A link into a folder that's itself a link out
        // of the server folder leads outside it.
        private static Path resolved(Path path) {
            Path existing = path;
            Path rest = existing.getFileSystem().getPath("");
            while (existing != null && !Files.exists(existing)) {
                Path name = existing.getFileName();
                rest = name == null ? rest : name.resolve(rest);
                existing = existing.getParent();
            }
            if (existing == null) {
                return path;
            }
            try {
                return existing.toRealPath().resolve(rest).normalize();
            } catch (IOException e) {
                return path;
            }
        }

        private void writeFile(Tree.Node node, Path out, String path) throws IOException {
            MessageDigest digest = Hash.digest();
            long size = 0;
            try (OutputStream stream = new DigestOutputStream(Files.newOutputStream(out, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), digest)) {
                for (Hash blob : node.blobs()) {
                    byte[] data = repository.readBlob(blob);
                    throttle.acquire(data.length);
                    stream.write(data);
                    size += data.length;
                }
            }
            if (size != node.contentSize() || !Hash.of(digest.digest()).equals(node.contentHash())) {
                throw new CorruptException(path + " didn't come back as it was saved.");
            }
            sync(out);
            files++;
            bytes += size;
            if (files % 500 == 0) {
                progress.accept(files + " files written");
            }
        }

        private void writeRegion(RegionLayout layout, Path out, String path) throws IOException {
            try (FileChannel channel = FileChannel.open(out, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                writeAt(channel, ByteBuffer.wrap(layout.header()), 0);
                for (int i = 0; i < RegionLayout.CHUNKS; i++) {
                    if (!layout.present(i)) {
                        continue;
                    }
                    byte[] record = repository.readBlob(layout.blob(i));
                    if (record.length != layout.recordLength(i)) {
                        throw new CorruptException(path + ": chunk " + i + " has the wrong length.");
                    }
                    throttle.acquire(record.length);
                    writeAt(channel, ByteBuffer.wrap(record), RegionLayout.sectorOffset(layout.location(i)) * RegionLayout.SECTOR);
                    chunks++;
                }
                if (channel.size() < layout.fileLength()) {
                    // Zero-fill to the saved length, sector padding included.
                    writeAt(channel, ByteBuffer.allocate(1), layout.fileLength() - 1);
                }
                if (channel.size() != layout.fileLength()) {
                    throw new CorruptException(path + " came back " + channel.size() + " bytes long, not "
                        + layout.fileLength() + ".");
                }
                channel.force(true);
            }
            files++;
            bytes += layout.fileLength();
        }

        private void finish(Path out, Tree.Node node) throws IOException {
            setMode(out, node.mode());
            Files.setLastModifiedTime(out, FileTime.fromMillis(node.mtime()));
        }

        // Folder times last, since writing into a folder changes its time.
        void finishFolders() throws IOException {
            for (int i = folders.size() - 1; i >= 0; i--) {
                Path out = (Path) folders.get(i)[0];
                Tree.Node node = (Tree.Node) folders.get(i)[1];
                setMode(out, node.mode());
                Files.setLastModifiedTime(out, FileTime.fromMillis(node.mtime()));
            }
        }
    }

    private static void writeAt(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long at = position;
        while (buffer.hasRemaining()) {
            at += channel.write(buffer, at);
        }
    }

    private static void sync(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void setMode(Path file, int mode) {
        if (mode < 0) {
            return;
        }
        Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
        for (PosixFilePermission permission : PosixFilePermission.values()) {
            if ((mode & (1 << (8 - permission.ordinal()))) != 0) {
                permissions.add(permission);
            }
        }
        try {
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException | IOException e) {
            // Not a POSIX system, or not ours to change.
        }
    }

    private static BasicFileAttributes attributes(Path file) {
        try {
            return Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            return null;
        }
    }

    static String join(String path, String name) {
        return path.isEmpty() ? name : path + "/" + name;
    }
}
