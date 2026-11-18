package co.xenastudios.zchat.api;

/**
 * Which kind of message a player is sending.
 *
 * @since 3.2.0
 */
public enum MessageKind {
    /** A chat message, seen by everyone. */
    CHAT,
    /** A private message, with {@code /msg} or {@code /reply}. */
    PRIVATE_MESSAGE
}
