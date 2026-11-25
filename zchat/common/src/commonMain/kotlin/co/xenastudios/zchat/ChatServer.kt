package co.xenastudios.zchat

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.config.Sound
import co.xenastudios.core.common.text.Component

/**
 * What zChat needs from the server it's on, as each platform's adapter does it: the one thing
 * its shared code can't do itself. Each adapter picks how once, at startup.
 *
 * Chat arrives on many threads at once (a chat thread per message on Paper, Folia and Spigot),
 * so everything here but [playSound] may be called from any thread, and answers from memory.
 */
public interface ChatServer {
    /** [player]'s name, if they're online. */
    public fun name(player: PlayerRef): String?

    /** [player]'s display name (a nickname plugin's, with its colours), or their name. */
    public fun displayName(player: PlayerRef): Component

    /** Whether [player] has the permission [node]. `false` if they're offline. */
    public fun has(player: PlayerRef, node: String): Boolean

    /** Whether the server shows [target] to [viewer] (Bukkit's `canSee`): another plugin's vanish. */
    public fun canSee(viewer: PlayerRef, target: PlayerRef): Boolean

    /** Sends [message] to [player], if they're online. */
    public fun send(player: PlayerRef, message: Component)

    /** Logs [message] on the console as a chat line, colours and all. */
    public fun console(message: Component)

    /** Plays [sound] to [player], where they stand. On the player's thread. */
    public fun playSound(player: PlayerRef, sound: Sound)

    /**
     * A PlaceholderAPI value (`vault_prefix`, written `%vault_prefix%` in a format) for
     * [player], as the placeholder plugin gives it (`&` codes and all), or `null` where there's
     * no such plugin. Asked on the chat thread.
     */
    public fun placeholder(player: PlayerRef, identifier: String): String? = null

    /** Whether a placeholder plugin is there, so a format's `%placeholders%` can be filled in. */
    public val placeholders: Boolean get() = false

    /**
     * The players a target selector (`@a`, `@p[distance=..10]`) names for [source], where the
     * server has selectors for plugins (Paper, not Folia); `null` where it hasn't, so the
     * selector is read as a name. Throws [IllegalArgumentException] for one it can't read.
     */
    public fun select(source: CommandSource, selector: String): List<PlayerRef>? = null
}
