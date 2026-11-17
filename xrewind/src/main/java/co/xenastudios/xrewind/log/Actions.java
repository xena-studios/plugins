package co.xenastudios.xrewind.log;

/** The action numbers stored in each table. Stored, so never renumbered. */
public final class Actions {

    private Actions() {}

    // block.action
    public static final byte BREAK = 0;
    public static final byte PLACE = 1;
    public static final byte CLICK = 2;

    // container.action
    public static final byte REMOVE = 0;
    public static final byte ADD = 1;

    // entity.action
    public static final byte KILL = 0;

    // text.kind
    public static final byte SIGN = 0;
    public static final byte CHAT = 1;
    public static final byte COMMAND = 2;

    // session.action
    public static final byte QUIT = 0;
    public static final byte JOIN = 1;
}
