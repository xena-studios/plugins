package co.xenastudios.zchat.api

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventBus

/**
 * zChat's developer API, the same on Paper, Folia, Spigot and Fabric: mutes and hidden players
 * from other plugins, placeholders for chat formats, broadcasts in zChat's style, what zChat
 * knows about chat, and chat's events.
 *
 * Get it with [ZChat.api]. On Bukkit it's also in the services manager while zChat is enabled.
 * Every method may be called from any thread, answers from memory and returns at once.
 *
 * Players are by [PlayerRef] (a UUID): from Java, `PlayerRefs.of(player.getUniqueId())`.
 * Text is in zChat's markup, MiniMessage's tags (`<red>`, `<bold>`, `<click:...>`) and the
 * theme's (`<prefix>`, `<highlight>`, `<success>`, `<error>`).
 *
 * @since 4.0.0
 */
public interface ZChatApi {
    /** The running zChat's version, such as `4.0.0`. */
    public val version: String

    /**
     * Chat's events: [co.xenastudios.zchat.api.event.ChatMessageEvent],
     * [co.xenastudios.zchat.api.event.ChatMessageSentEvent],
     * [co.xenastudios.zchat.api.event.MentionEvent],
     * [co.xenastudios.zchat.api.event.PrivateMessageEvent] and
     * [co.xenastudios.zchat.api.event.FilterMatchEvent]. On Bukkit each is also fired as a
     * Bukkit event in `co.xenastudios.zchat.api.bukkit`, so Skript and plugins listening the
     * Bukkit way see them.
     */
    public val events: EventBus

    /**
     * Asks [provider], before every chat message and private message a player sends, whether
     * they're muted. Providers are asked in the order they were registered, and the first
     * reason given wins. One that throws is logged and removed, so it can't break chat.
     * Registering the same provider again does nothing. [owner] names it in the console
     * (your plugin's name).
     */
    public fun registerMuteProvider(owner: String, provider: MuteProvider)

    /** Stops asking [provider]. */
    public fun unregisterMuteProvider(provider: MuteProvider)

    /**
     * Asks [hook] whether a player is hidden (vanished) from another, so zChat doesn't
     * complete, mention or private-message them for players who can't see them. Hooks are
     * asked as well as the server's own visibility; one that throws is logged and removed.
     */
    public fun registerHiddenPlayers(owner: String, hook: HiddenPlayers)

    /** Stops asking [hook]. */
    public fun unregisterHiddenPlayers(hook: HiddenPlayers)

    /**
     * Gives chat formats a `<name>` tag ([name]: lower-case letters, digits, `_` and `-`), filled
     * in per sender by [placeholder], such as `<balance>` from an economy plugin. A name zChat's
     * formats already use (`player`, `displayname`, `message`, `server`) or a theme tag can't
     * be taken. Registering a name again replaces it.
     *
     * @throws IllegalArgumentException if [name] can't be a tag, or is zChat's own
     */
    public fun registerPlaceholder(name: String, placeholder: ChatPlaceholder)

    /** Takes away the `<name>` tag [name] gave formats. */
    public fun unregisterPlaceholder(name: String)

    /**
     * Sends [message] (zChat's markup, without a prefix) to every player who hasn't hidden chat
     * with `/togglechat`, and to the console, in zChat's style: the theme's prefix and colour.
     */
    public fun broadcast(message: String)

    /**
     * Sends [message] to one player in zChat's style, unless they hid chat with `/togglechat`.
     *
     * @return `true` if it was sent; `false` if they hid chat or aren't online
     */
    public fun send(player: PlayerRef, message: String): Boolean

    /** Whether [player] hid other players' chat with `/togglechat`. Always `false` while `/togglechat` is off. */
    public fun hasChatHidden(player: PlayerRef): Boolean

    /** Whether chat is muted for everyone with `/mutechat`. */
    public fun isChatMuted(): Boolean

    /**
     * Which chat format [player] gets: the name of their group under `formatting.groups` with
     * the highest weight, or [DEFAULT_FORMAT]. It checks the player's permissions, so the
     * player must be online; one who isn't gets [DEFAULT_FORMAT].
     */
    public fun getFormatName(player: PlayerRef): String

    public companion object {
        /** What [getFormatName] returns for a player in none of the groups: the `default-format`. */
        public const val DEFAULT_FORMAT: String = "default"
    }
}

/** Which kind of message a player is sending. */
public enum class MessageKind {
    /** A chat message, seen by everyone. */
    CHAT,

    /** A private message, with `/msg` or `/reply`. */
    PRIVATE_MESSAGE,
}

/**
 * Says whether a player is muted, for a punishment plugin (xBans). Register one with
 * [ZChatApi.registerMuteProvider]; zChat asks it before every chat message and private message.
 *
 * It's asked on the thread the message is sent from (a chat thread, many at once) on the hot
 * path of every message, so it must answer from memory: never query a database or wait.
 */
public fun interface MuteProvider {
    /**
     * `null` if [player] may send it; otherwise what to tell them, in zChat's markup, and the
     * message isn't sent.
     */
    public fun muteReason(player: PlayerRef, kind: MessageKind): String?
}

/**
 * Says whether a player is hidden from another, for a vanish plugin (xVanish). Register one
 * with [ZChatApi.registerHiddenPlayers]. Asked on chat threads, from memory only.
 */
public fun interface HiddenPlayers {
    /** Whether [viewer] mustn't see [target] in chat: not mentioned, completed or messaged. */
    public fun isHidden(viewer: PlayerRef, target: PlayerRef): Boolean
}

/**
 * A `<name>` tag's value for one sender: see [ZChatApi.registerPlaceholder]. Asked once per
 * message, on the chat thread, from memory only.
 */
public fun interface ChatPlaceholder {
    /**
     * The value for [player], in zChat's markup (colours and styles only: clicks and hovers are
     * taken out), or `null` for nothing.
     */
    public fun value(player: PlayerRef): String?
}
