package co.xenastudios.xbackup.backup;

import co.xenastudios.xbackup.store.CorruptException;
import co.xenastudios.xbackup.store.Hash;
import co.xenastudios.xbackup.store.RegionLayout;
import co.xenastudios.xbackup.store.Repository;
import co.xenastudios.xbackup.store.Tree;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * What an earlier read found at each path, so unchanged files and chunks are reused
 * instead of read again: the last snapshot, or the first pass of this backup.
 */
interface Reference {

    /** The earlier entry at a path, with when it was read; null if there's none. */
    Previous find(String path) throws IOException;

    /** A region layout an entry points to; null if it can't be read (a lost pack). */
    RegionLayout region(Hash id) throws IOException;

    /**
     * An earlier entry.
     *
     * @param readAt when that read started, in milliseconds: a file modified within a
     *     moment of it may have changed again without its size or time showing it
     */
    record Previous(Tree.Node node, long readAt) {}

    Reference NONE = new Reference() {
        @Override
        public Previous find(String path) {
            return null;
        }

        @Override
        public RegionLayout region(Hash id) throws IOException {
            throw new IOException("no reference");
        }
    };

    /** A snapshot's tree, walked as paths are asked for. */
    static Reference of(Repository repository, Hash root, long readAt) {
        return new Reference() {
            private final Map<String, Tree> folders = new HashMap<>();

            @Override
            public Previous find(String path) throws IOException {
                int slash = path.lastIndexOf('/');
                Tree folder = folder(slash < 0 ? "" : path.substring(0, slash));
                Tree.Node node = folder == null ? null : folder.get(path.substring(slash + 1));
                return node == null ? null : new Previous(node, readAt);
            }

            private Tree folder(String path) throws IOException {
                if (folders.containsKey(path)) {
                    return folders.get(path);
                }
                Tree tree;
                if (path.isEmpty()) {
                    tree = read(root);
                } else {
                    int slash = path.lastIndexOf('/');
                    Tree parent = folder(slash < 0 ? "" : path.substring(0, slash));
                    Tree.Node node = parent == null ? null : parent.get(path.substring(slash + 1));
                    tree = node != null && node.kind() == Tree.Kind.DIR ? read(node.ref()) : null;
                }
                // Folders are visited in order, so only the path being walked needs keeping.
                if (folders.size() > 256) {
                    folders.clear();
                }
                folders.put(path, tree);
                return tree;
            }

            // A folder whose listing went with a lost pack is read again, not reused.
            private Tree read(Hash id) throws IOException {
                try {
                    return repository.tree(id);
                } catch (CorruptException e) {
                    return null;
                }
            }

            @Override
            public RegionLayout region(Hash id) throws IOException {
                try {
                    return repository.region(id);
                } catch (CorruptException e) {
                    return null;
                }
            }
        };
    }

    /** What the first pass read, on top of another reference for everything it didn't. */
    static Reference overlay(Map<String, Tree.Node> nodes, Map<Hash, RegionLayout> regions, long readAt,
                             Reference under) {
        return new Reference() {
            @Override
            public Previous find(String path) throws IOException {
                Tree.Node node = nodes.get(path);
                return node != null ? new Previous(node, readAt) : under.find(path);
            }

            @Override
            public RegionLayout region(Hash id) throws IOException {
                RegionLayout region = regions.get(id);
                return region != null ? region : under.region(id);
            }
        };
    }
}
