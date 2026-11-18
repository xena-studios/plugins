package co.xenastudios.xrewind.api;

/**
 * A kind of logged change, as {@code a:} in {@code /xr lookup} names them.
 *
 * @since 1.1.0
 */
public enum Action {
    /** A block broken or removed ({@code a:-block}). */
    BREAK,
    /** A block placed or changed ({@code a:+block}). */
    PLACE,
    /** A block clicked: a door, lever or button ({@code a:click}). */
    CLICK,
    /** Items put into a container ({@code a:+container}). */
    ADD,
    /** Items taken out of a container ({@code a:-container}). */
    REMOVE,
    /** An entity killed ({@code a:kill}). */
    KILL,
    /** A sign written ({@code a:sign}). */
    SIGN,
    /** A chat message ({@code a:chat}). */
    CHAT,
    /** A command ({@code a:command}). */
    COMMAND,
    /** A player joined ({@code a:+session}). */
    JOIN,
    /** A player quit ({@code a:-session}). */
    QUIT
}
