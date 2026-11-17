package co.xenastudios.xrewind.log;

/** The action numbers stored in each table. Stored, so never renumbered. */
public final class Actions {

    private Actions() {}

    // block.action
    public static final byte BREAK = 0;
    public static final byte PLACE = 1;

    // session.action
    public static final byte QUIT = 0;
    public static final byte JOIN = 1;
}
