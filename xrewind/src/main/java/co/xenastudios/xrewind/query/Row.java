package co.xenastudios.xrewind.query;

/**
 * One logged change as a lookup shows it, names filled in.
 *
 * @param action the table's action number (for text, its kind)
 * @param thing what it was about: a block state, an item or entity type, a message
 * @param after the block state after, for block changes; otherwise null
 * @param amount how many items, for container changes
 * @param extra for rollbacks: a block's contents, or a container change's item; otherwise null
 */
public record Row(Table table, long id, int time, String user, String world, int x, int y, int z, byte action,
                  String thing, String after, boolean rolledBack, int amount, byte[] extra) {

    public enum Table { BLOCK, CONTAINER, KILL, TEXT, SESSION }

    /** The block state before, for block changes. */
    public String before() {
        return thing;
    }
}
