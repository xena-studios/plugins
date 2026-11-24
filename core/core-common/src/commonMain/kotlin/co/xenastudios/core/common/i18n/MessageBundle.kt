package co.xenastudios.core.common.i18n

/**
 * Messages a plugin (or the core) sends, declared once as an `object`, in the order its
 * `lang/<tag>.yml` files list them. Each has a key, its English text, a comment for owners
 * and translators, and the placeholders it fills when it's sent:
 *
 * ```
 * object HomesMessages : MessageBundle() {
 *     val set: MessageKey = message(
 *         "homes.set", "<prefix>Home <highlight><name></highlight> set.",
 *         "When a player sets a home.",
 *         placeholders = listOf("name"),
 *     )
 * }
 * ```
 *
 * English lives here, in code, and is the last fallback; every other language is a file
 * (`lang/de.yml`) shipped in the jar. A message's English text may use the markup's styles,
 * the theme's tags and its own [MessageKey.placeholders], nothing else, so a typo in a tag
 * fails when the bundle is first used (in its tests), not on a server.
 */
public abstract class MessageBundle {
    private val declared = mutableListOf<MessageKey>()

    /** Every message, in order. */
    public val messages: List<MessageKey> get() = declared

    /**
     * A message: its [key] (`homes.set`: lower-case words joined with `.`), its [english]
     * text, the [comment] written above it, the [placeholders] it fills when sent, and the
     * English texts it used to have ([formerEnglish]): an owner whose file still has one of
     * those never edited it, so it's moved to the new text (see [MessageKey.formerEnglish]).
     */
    protected fun message(
        key: String,
        english: String,
        vararg comment: String,
        placeholders: List<String> = emptyList(),
        formerEnglish: List<String> = emptyList(),
    ): MessageKey {
        require(KEY.matches(key)) { "message key \"$key\": use lower-case words of a-z, 0-9, - and _, joined with ." }
        require(key.substringBefore('.') != LangFiles.RESERVED) { "message key $key: ${LangFiles.RESERVED} is the files' own" }
        require(declared.none { it.key == key }) { "message $key declared twice" }
        for (name in placeholders) {
            require(PLACEHOLDER.matches(name)) { "$key: placeholder \"$name\" should be lower-case a-z, 0-9, - and _" }
            require(MarkupTags.kind(name) == MarkupTags.Kind.OTHER) { "$key: <$name> is a tag of the markup or the theme, not a placeholder" }
        }
        val used = MarkupTags.names(english).toSet()
        for (name in placeholders) require(name in used) { "$key declares <$name>, but its English text doesn't use it" }
        for (name in used) {
            require(MarkupTags.kind(name) != MarkupTags.Kind.OTHER || name in placeholders) {
                "$key uses <$name>, which is neither a placeholder it declares nor a tag of the markup or the theme"
            }
        }
        val message = MessageKey(this, declared.size, key, english, comment.flatMap { it.split('\n') }, placeholders.toList(), formerEnglish.toList())
        declared += message
        return message
    }

    private companion object {
        val KEY = Regex("[a-z0-9][a-z0-9_-]*(\\.[a-z0-9][a-z0-9_-]*)*")
        val PLACEHOLDER = Regex("[a-z0-9_-]+")
    }
}

/**
 * One message of a [MessageBundle]. Send it through a [MessageDrawer], or render it with
 * [Translations.render].
 */
public class MessageKey internal constructor(
    /** The bundle it's declared in. */
    public val bundle: MessageBundle,
    internal val index: Int,
    /** Its key in every language's file: `homes.set`. */
    public val key: String,
    /** Its English text, in markup. */
    public val english: String,
    /** What it's for, written above it in each file, a line each. */
    public val comment: List<String>,
    /** The placeholders it fills when sent, by name: `name` for `<name>`. */
    public val placeholders: List<String>,
    /**
     * The English texts it used to have. A file whose message is one of these (or a former
     * translation, for another language) was never edited by its owner, so it's given the
     * current text on the next load; an edited one is kept.
     */
    public val formerEnglish: List<String>,
) {
    override fun toString(): String = key
}

/**
 * The bundles one plugin's `lang/` files hold, in file order: usually the core's own
 * ([CoreMessages]) and the plugin's. A key may appear in only one.
 */
public class MessageCatalog private constructor(public val bundles: List<MessageBundle>) {

    /** Every message, bundle by bundle, in file order. */
    public val messages: List<MessageKey> = bundles.flatMap { it.messages }

    private val offsets = IntArray(bundles.size).also { offsets ->
        var total = 0
        bundles.forEachIndexed { i, bundle ->
            offsets[i] = total
            total += bundle.messages.size
        }
    }

    private val byKey: Map<String, MessageKey> = HashMap<String, MessageKey>(messages.size * 2).also { map ->
        for (message in messages) require(map.put(message.key, message) == null) { "message ${message.key} is in two bundles" }
    }

    /** How many messages there are. */
    public val size: Int get() = messages.size

    /** The message with this [key], or `null` if none has it. */
    public operator fun get(key: String): MessageKey? = byKey[key]

    /** Where [message] is in [messages], or -1 if it isn't in this catalog. */
    public fun slot(message: MessageKey): Int {
        for (i in bundles.indices) {
            if (bundles[i] === message.bundle) return offsets[i] + message.index
        }
        return -1
    }

    public companion object {
        /** A catalog of these [bundles], in this order. */
        public fun of(vararg bundles: MessageBundle): MessageCatalog {
            require(bundles.isNotEmpty()) { "a catalog needs a bundle" }
            require(bundles.toSet().size == bundles.size) { "a bundle is in the catalog twice" }
            return MessageCatalog(bundles.toList())
        }
    }
}
