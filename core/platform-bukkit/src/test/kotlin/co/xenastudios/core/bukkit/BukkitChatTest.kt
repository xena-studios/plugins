package co.xenastudios.core.bukkit

import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.bukkit.modern.ChatRefs
import co.xenastudios.core.common.chat.ChatRendering
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Style
import co.xenastudios.core.common.text.TextColor
import co.xenastudios.core.testing.RecordingLog
import net.md_5.bungee.api.chat.BaseComponent
import org.bukkit.entity.Player
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.plugin.EventExecutor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.bukkit.event.EventPriority as BukkitPriority

/** The chat bridge's Spigot path, through Bukkit's own plugin manager and chat event (1.8.8's API). */
class BukkitChatTest {
    private val spigot = setOf("org.spigotmc.SpigotConfig", "net.md_5.bungee.api.chat.BaseComponent", "org.bukkit.entity.Player\$Spigot")
    private val log = RecordingLog()
    private val alexInbox = Inbox()
    private val samInbox = Inbox()
    private lateinit var alex: Player
    private lateinit var sam: Player
    private lateinit var chat: SpigotChat
    private val other = object : Listener {}

    @BeforeTest
    fun setUp() {
        FakeServer.reset()
        alex = fakePlayer("Alex", alexInbox)
        sam = fakePlayer("Sam", samInbox)
        FakeServer.players = listOf(alex, sam)
        val plugin = FakeServer.plugin()
        val text = BukkitText.pick(BukkitServer.detect({ it in spigot }, "1.8.8", "CraftBukkit", adventure = false), FakeServer.server)
        chat = SpigotChat(plugin, text, ChatRefs(plugin), EventPriority.LOW, log)
    }

    @AfterTest
    fun tearDown() {
        chat.close()
        HandlerList.unregisterAll(other)
    }

    private fun say(player: Player, text: String): AsyncPlayerChatEvent {
        val event = AsyncPlayerChatEvent(false, player, text, LinkedHashSet(FakeServer.players))
        FakeServer.plugins.callEvent(event)
        return event
    }

    private fun line(text: String) = Component("[Member] ", Style(color = TextColor.NAMED.getValue("gray")), listOf(Component.text(text)))

    @Test
    fun unheardChatIsLeftAlone() {
        val event = say(alex, "hi")
        assertEquals("<%1\$s> %2\$s", event.format)
        assertEquals(2, event.recipients.size)
    }

    @Test
    fun aPlainLineForEveryoneBecomesTheFormat() {
        chat.listen { it.rendering = ChatRendering.same { e -> line("${e.senderName}: ${e.message} 100%") } }
        val event = say(alex, "hi")
        assertEquals("§7[Member] Alex: hi 100%%", event.format)
        assertEquals(2, event.recipients.size)
        assertTrue(alexInbox.received.isEmpty())
    }

    @Test
    fun aLineWithAHoverIsSentAsComponentsAndLoggedAsTheFormat() {
        chat.listen {
            it.rendering = ChatRendering.same { e -> Component(e.senderName, Style(hover = Component.text("Member")), listOf(Component.text(": ${e.message}"))) }
        }
        val event = say(alex, "hi")
        assertTrue(event.recipients.isEmpty())
        assertEquals("Alex: hi", event.format)
        assertEquals("Alex: hi", assertIs<BaseComponent>(samInbox.received.single()).toPlainText())
        // One drawing, the same object to everyone.
        assertTrue(alexInbox.received.single() === samInbox.received.single())
    }

    @Test
    fun aLinePerViewerIsDrawnForEach() {
        chat.listen { it.rendering = ChatRendering.perViewer { e, viewer -> Component.text(if (viewer == null) "console" else "${e.message} for one") } }
        val event = say(alex, "hi")
        assertEquals("hi for one", assertIs<BaseComponent>(samInbox.received.single()).toPlainText())
        assertFalse(alexInbox.received.single() === samInbox.received.single())
        assertEquals("console", event.format)
    }

    @Test
    fun cancellingAndRecipientsAndMessageReachTheEvent() {
        chat.listen {
            it.recipients.removeIf { player -> player.toString() == sam.uniqueId.toString() }
            it.message = it.message.uppercase()
            if (it.message == "STOP") it.isCancelled = true
        }
        val event = say(alex, "hello")
        assertEquals("HELLO", event.message)
        assertEquals(setOf(alex), event.recipients)
        assertTrue(say(alex, "stop").isCancelled)
    }

    @Test
    fun theRenderingSeesALaterPluginsMessageAndSentListenersHearLast() {
        val order = ArrayList<String>()
        FakeServer.plugins.registerEvent(AsyncPlayerChatEvent::class.java, other, BukkitPriority.HIGH, EventExecutor { _, e ->
            (e as AsyncPlayerChatEvent).message = e.message + "!"
            order += "other"
        }, FakeServer.plugin("Other"))
        chat.listen { it.rendering = ChatRendering.same { e -> Component.text(e.message) }; order += "listen" }
        chat.onSent { order += "sent:${it.chat.message}" }
        val event = say(alex, "hi")
        assertEquals("hi!", event.format)
        assertEquals(listOf("listen", "other", "sent:hi!"), order)
    }

    @Test
    fun aRenderingThatThrowsLeavesTheServersFormat() {
        chat.listen { it.rendering = ChatRendering.same { error("broken") } }
        assertEquals("<%1\$s> %2\$s", say(alex, "hi").format)
        assertEquals(1, log.errors.size)
    }

    @Test
    fun closingUnregisters() {
        chat.listen { it.isCancelled = true }
        chat.close()
        assertFalse(say(alex, "hi").isCancelled)
    }
}
