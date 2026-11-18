package co.xenastudios.xbans.api;

/**
 * What a punishment does.
 *
 * @since 1.0.0
 */
public enum PunishmentType {
    /** Keeps the player from joining. */
    BAN,
    /** Keeps the player from chatting, from the configured commands, and from writing signs and books. */
    MUTE,
    /** A warning on the player's record. Enough of them can set off an action, such as a mute. */
    WARN,
    /** Disconnects the player once. Never active: it's only history. */
    KICK;

    /**
     * Whether a punishment of this type stays in force until it ends or is revoked.
     *
     * @return true for bans, mutes and warnings; false for kicks
     * @since 1.0.0
     */
    public boolean lasts() {
        return this != KICK;
    }
}
