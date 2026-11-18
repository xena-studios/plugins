package co.xenastudios.xbackup.store;

/**
 * Where one blob sits in its pack.
 *
 * @param storedLength its bytes in the pack, compressed or not
 * @param rawLength its bytes once inflated: the content its id is the hash of
 */
public record PackEntry(Hash id, BlobType type, boolean compressed, long offset, int storedLength, int rawLength) {}
