package co.xenastudios.xbackup.remote;

import co.xenastudios.xbackup.restore.Restore;
import co.xenastudios.xbackup.store.Backend;
import co.xenastudios.xbackup.store.Hash;
import co.xenastudios.xbackup.store.Index;
import co.xenastudios.xbackup.store.LocalBackend;
import co.xenastudios.xbackup.store.Pack;
import co.xenastudios.xbackup.store.RegionLayout;
import co.xenastudios.xbackup.store.Repository;
import co.xenastudios.xbackup.store.Tree;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Restoring straight from a remote: rather than one request per blob, the files a restore
 * needs are downloaded whole into a local cache with the repository's layout, which is then
 * restored from like any local repository. First the small files and the packs holding the
 * snapshots' structure, then only the data packs the chosen paths use.
 */
public final class Fetch {

    private Fetch() {}

    /** Copies everything but the data packs into {@code cache}, and opens it. */
    public static Repository metadata(Backend remote, Path cache, Consumer<String> progress) throws IOException {
        Files.createDirectories(cache);
        LocalBackend local = new LocalBackend(cache);
        progress.accept("reading the remote's index");
        Set<Hash> present = new HashSet<>();
        for (Backend.FileInfo file : remote.list("")) {
            String name = file.name();
            Hash pack = name.startsWith("data/") ? Pack.idOf(name) : null;
            if (pack != null) {
                present.add(pack);
            }
            if (name.equals("config") || name.startsWith("index/") || name.startsWith("snapshots/")
                || name.startsWith("keep/")) {
                copy(remote, local, name, file.size());
            }
        }
        Repository repository = Repository.open(local, cache.resolve("tmp"), false);
        // The remote can keep index files that list packs it no longer has (kept for packs
        // this server lost); only the packs it has are used.
        repository.keepPacks(present);
        Set<Hash> packs = new HashSet<>();
        repository.index().forEach(location -> {
            if (location.entry().type().isMetadata()) {
                packs.add(location.pack());
            }
        });
        download(remote, local, packs, "structure", progress);
        return repository;
    }

    /** Downloads the data packs the items use into the cache repository. */
    public static void data(Backend remote, Repository cached, List<Restore.Item> items, Consumer<String> progress)
        throws IOException {
        Set<Hash> packs = new HashSet<>();
        Index index = cached.index();
        Set<Hash> seen = new HashSet<>();
        for (Restore.Item item : items) {
            collect(cached, item.node(), index, packs, seen);
        }
        download(remote, (LocalBackend) cached.backend(), packs, "data", progress);
    }

    private static void collect(Repository repository, Tree.Node node, Index index, Set<Hash> packs, Set<Hash> seen)
        throws IOException {
        switch (node.kind()) {
            case DIR -> {
                if (seen.add(node.ref())) {
                    for (Tree.Node child : repository.tree(node.ref()).nodes()) {
                        collect(repository, child, index, packs, seen);
                    }
                }
            }
            case REGION -> {
                RegionLayout layout = repository.region(node.ref());
                for (int i = 0; i < RegionLayout.CHUNKS; i++) {
                    if (layout.blob(i) != null) {
                        add(index, layout.blob(i), packs);
                    }
                }
            }
            case FILE, SQLITE -> {
                for (Hash blob : node.blobs()) {
                    add(index, blob, packs);
                }
            }
            case LINK -> {
                // Nothing stored.
            }
        }
    }

    private static void add(Index index, Hash blob, Set<Hash> packs) {
        Index.Location location = index.find(blob);
        if (location != null) {
            packs.add(location.pack());
        }
    }

    private static void download(Backend remote, LocalBackend local, Set<Hash> packs, String what,
                                 Consumer<String> progress) throws IOException {
        int done = 0;
        for (Hash pack : packs) {
            String name = Pack.fileName(pack);
            if (Files.exists(local.root().resolve(name))) {
                done++;
                continue;
            }
            progress.accept("downloading " + what + ": " + done + " of " + packs.size() + " packs");
            copy(remote, local, name, -1);
            done++;
        }
    }

    private static void copy(Backend remote, LocalBackend local, String name, long size) throws IOException {
        Path temp = local.newTemp(".part");
        try (InputStream in = remote.get(name)) {
            long copied = Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            if (size >= 0 && copied != size) {
                throw new IOException(name + " came down as " + copied + " bytes, not " + size + ".");
            }
            local.put(name, temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
