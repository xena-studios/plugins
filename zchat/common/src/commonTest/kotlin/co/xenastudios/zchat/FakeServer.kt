package co.xenastudios.zchat

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.config.Sound
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.testing.FakeCommandSource
import kotlin.uuid.Uuid

/** A server of fake players for zChat's tests: what each was sent, heard and may see. */
class FakeServer : ChatServer, LanguageSource {
    class Player(val ref: PlayerRef, val source: FakeCommandSource) {
        val messages = ArrayList<Component>()
        val sounds = ArrayList<Sound>()
        var locale: String? = null
        var displayName: Component? = null
        val hidden = HashSet<PlayerRef>()

        /** Everything they were sent, as plain text: messages and command replies. */
        val lines: List<String> get() = messages.map { it.plain() } + source.replies
    }

    val players = LinkedHashMap<PlayerRef, Player>()
    val console = ArrayList<Component>()
    val values = HashMap<String, String>()
    var selector: ((CommandSource, String) -> List<PlayerRef>?)? = null

    fun add(source: FakeCommandSource): Player {
        val ref = source.player ?: PlayerRef.of(Uuid.random())
        return Player(ref, source).also { players[ref] = it }
    }

    override fun name(player: PlayerRef): String? = players[player]?.source?.name
    override fun displayName(player: PlayerRef): Component = players[player]?.displayName ?: Component.text(name(player) ?: "?")
    override fun has(player: PlayerRef, node: String): Boolean = players[player]?.source?.hasPermission(node) == true
    override fun canSee(viewer: PlayerRef, target: PlayerRef): Boolean = target !in (players[viewer]?.hidden ?: emptySet<PlayerRef>())
    override fun send(player: PlayerRef, message: Component) {
        players[player]?.messages?.add(message)
    }
    override fun console(message: Component) {
        console += message
    }
    override fun playSound(player: PlayerRef, sound: Sound) {
        if (!sound.isNone) players[player]?.sounds?.add(sound)
    }
    override fun placeholder(player: PlayerRef, identifier: String): String? = values[identifier]
    override val placeholders: Boolean get() = values.isNotEmpty()
    override fun select(source: CommandSource, selector: String): List<PlayerRef>? = this.selector?.invoke(source, selector)

    override fun clientLocale(player: PlayerRef): String? = players[player]?.locale
}
