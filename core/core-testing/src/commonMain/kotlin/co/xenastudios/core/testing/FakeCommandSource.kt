package co.xenastudios.core.testing

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.text.Component
import kotlin.uuid.Uuid

/**
 * Someone running commands in a test: a player (with a [player] reference) or the console, with
 * the [permissions] a test gives and takes, and every reply kept in [replies].
 *
 * ```kotlin
 * val steve = FakeCommandSource.player("Steve", "xhomes.set")
 * dispatcher.dispatch(steve, "xhomes set base")
 * assertEquals(listOf("Home base set."), steve.replies)
 * ```
 */
public class FakeCommandSource(
    override val name: String,
    override val player: PlayerRef?,
    permissions: Set<String> = emptySet(),
    /** Whether it has every permission, as the console and operators do. */
    public var everything: Boolean = false,
) : CommandSource {
    /** The nodes it has. */
    public val permissions: MutableSet<String> = permissions.toMutableSet()

    private val received = mutableListOf<Component>()

    /** What it was sent, as plain text, oldest first. */
    public val replies: List<String> get() = received.map { it.plain() }

    /** What it was sent, as sent. */
    public val components: List<Component> get() = received.toList()

    override fun hasPermission(node: String): Boolean = everything || node in permissions

    override fun reply(message: Component) {
        received += message
    }

    /** Forgets what it was sent. */
    public fun clear() {
        received.clear()
    }

    public companion object {
        /** A player called [name], with a UUID made from the name, and these [permissions]. */
        public fun player(name: String, vararg permissions: String): FakeCommandSource =
            FakeCommandSource(name, PlayerRef.of(uuidOf(name)), permissions.toSet())

        /** The console, which has every permission. */
        public fun console(): FakeCommandSource = FakeCommandSource("CONSOLE", null, everything = true)

        private fun uuidOf(name: String): Uuid {
            val bytes = ByteArray(16)
            name.encodeToByteArray().forEachIndexed { i, b -> bytes[i % 16] = (bytes[i % 16] + b).toByte() }
            return Uuid.fromByteArray(bytes)
        }
    }
}
