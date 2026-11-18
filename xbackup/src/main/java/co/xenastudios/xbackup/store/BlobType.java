package co.xenastudios.xbackup.store;

/**
 * What a blob holds. Data blobs (file content, region chunks) and metadata blobs (trees,
 * region layouts) go into separate packs, so reading a snapshot's structure never means
 * fetching its data.
 */
public enum BlobType {
    /** File content, or one chunk's record from a region file. */
    DATA,
    /** A directory listing. */
    TREE,
    /** A region file's layout: where each chunk sits, its timestamp, and its blob. */
    REGION;

    private static final BlobType[] VALUES = values();

    static BlobType of(int code) throws CorruptException {
        if (code < 0 || code >= VALUES.length) {
            throw new CorruptException("unknown blob type " + code);
        }
        return VALUES[code];
    }

    /** Whether it's structure rather than content. */
    public boolean isMetadata() {
        return this != DATA;
    }
}
