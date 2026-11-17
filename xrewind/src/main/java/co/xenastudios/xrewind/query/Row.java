package co.xenastudios.xrewind.query;

/**
 * One logged change as a lookup shows it, names filled in.
 *
 * @param before the block state before, or null for a session
 * @param after the block state after, or null for a session
 */
public record Row(Table table, long id, int time, String user, String world, int x, int y, int z, byte action,
                  String before, String after, boolean rolledBack) {

    public enum Table { BLOCK, SESSION }
}
