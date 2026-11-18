package co.xenastudios.xstats.data;

/**
 * A player's numbers for one day, week or month. {@code key} says which one; a bucket from
 * an earlier day reads as empty, so nothing has to be cleared when a new day starts.
 */
public record Bucket(int key, long[] values) {

    public long get(int key, int index) {
        return key == this.key && index < values.length ? values[index] : 0;
    }
}
