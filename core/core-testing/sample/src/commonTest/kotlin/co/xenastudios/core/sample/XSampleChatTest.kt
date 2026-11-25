package co.xenastudios.core.sample

import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.testing.FakeChat
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** xSample's chat format, on the fake platform's chat: what a plugin's chat tests look like. */
class XSampleChatTest {
    private val platform = FakePlatform()
    private val chat = FakeChat(platform.log)
    private val steve = FakeCommandSource.player("Steve")
    private val alex = FakeCommandSource.player("Alex")
    private val players = object : OnlinePlayers {
        override fun find(name: String, viewer: CommandSource) = null
        override fun names(viewer: CommandSource) = emptyList<String>()
    }

    init {
        chat.join(steve.player!!, "Steve")
        chat.join(alex.player!!, "Alex")
    }

    @Test
    fun chatIsFormattedOnceForEveryone() {
        val sample = XSample(platform, players, chat = { chat }).apply { enable() }
        val said = chat.say(steve.player!!, "hello")
        assertEquals("[xSample] Steve: hello", said.seenBy(alex.player!!))
        assertEquals("[xSample] Steve: hello", said.console?.plain())
        assertEquals(1, said.draws)
        // The name carries a hover, which Spigot sends as components.
        assertNotNull(said.lines.getValue(alex.player!!).children[1].style.hover)
        sample.disable()
        assertTrue(chat.closed)
        assertEquals("<Steve> hi", chat.say(steve.player!!, "hi").seenBy(alex.player!!))
    }
}
