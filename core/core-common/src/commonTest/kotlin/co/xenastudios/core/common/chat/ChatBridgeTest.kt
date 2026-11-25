package co.xenastudios.core.common.chat

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.common.i18n.CATALOG
import co.xenastudios.core.common.i18n.DE
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.MessageDrawer
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.PlainRenderer
import co.xenastudios.core.testing.FakeChat
import co.xenastudios.core.testing.RecordingLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class ChatBridgeTest {
    private val log = RecordingLog()
    private val chat = FakeChat(log)
    private val alex = PlayerRef.of(Uuid.random())
    private val sam = PlayerRef.of(Uuid.random())
    private val kim = PlayerRef.of(Uuid.random())

    init {
        chat.join(alex, "Alex")
        chat.join(sam, "Sam")
        chat.join(kim, "Kim")
    }

    private val prefix = ChatRendering.same { Component.text("[Member] ${it.senderName}: ${it.message}") }

    @Test
    fun unheardChatKeepsTheServersFormat() {
        val said = chat.say(alex, "hi")
        assertNull(said.event)
        assertEquals("<Alex> hi", said.seenBy(sam))
    }

    @Test
    fun aSameRenderingIsDrawnOnceForEveryone() {
        chat.listen { it.rendering = prefix }
        val said = chat.say(alex, "hello")
        assertEquals("[Member] Alex: hello", said.seenBy(sam))
        assertEquals("[Member] Alex: hello", said.seenBy(kim))
        assertEquals("[Member] Alex: hello", said.console?.plain())
        assertEquals(1, said.draws)
    }

    @Test
    fun aRenderingByLanguageIsDrawnOncePerLanguage() {
        val locales = mapOf(alex to "de_de", sam to "de_at", kim to "en_us")
        val drawer = MessageDrawer(
            Translations.of(CATALOG, mapOf(DE to emptyMap())),
            object : LanguageSource { override fun clientLocale(player: PlayerRef): String? = locales[player] },
            PlainRenderer,
        )
        chat.listen {
            it.rendering = ChatRendering.byLanguage(drawer) { event, language -> Component.text("${language.tag} ${event.senderName}: ${event.message}") }
        }
        val said = chat.say(alex, "hallo")
        assertEquals("de Alex: hallo", said.seenBy(alex))
        assertEquals("de Alex: hallo", said.seenBy(sam))
        assertEquals("en Alex: hallo", said.seenBy(kim))
        // German and English: the console reads in default-language, English, with Kim.
        assertEquals(2, said.draws)
    }

    @Test
    fun aRenderingPerViewerIsDrawnForEach() {
        chat.listen { it.rendering = ChatRendering.perViewer { event, viewer -> Component.text(if (viewer == sam) "@ ${event.message}" else event.message) } }
        val said = chat.say(alex, "sam!")
        assertEquals("@ sam!", said.seenBy(sam))
        assertEquals("sam!", said.seenBy(kim))
        assertEquals(4, said.draws)
    }

    @Test
    fun listenersHearInOrderAndCanChangeTheMessageAndRecipients() {
        val heard = ArrayList<String>()
        chat.listen(EventPriority.HIGH) { heard += "high:${it.message}" }
        chat.listen(EventPriority.LOW) {
            heard += "low"
            it.message = it.message.replace("darn", "****")
            it.recipients.remove(kim)
        }
        val said = chat.say(alex, "darn it")
        assertEquals(listOf("low", "high:**** it"), heard)
        assertEquals("<Alex> **** it", said.seenBy(sam))
        assertNull(said.seenBy(kim))
        assertTrue(said.event!!.changed)
        assertEquals("darn it", said.event!!.originalMessage)
    }

    @Test
    fun aCancelledMessageGoesNowhereAndIsntSent() {
        var sent = 0
        chat.listen(EventPriority.LOW) { it.isCancelled = true }
        chat.listen { error("a listener that ignores cancelled messages heard one") }
        chat.onSent { sent++ }
        val said = chat.say(alex, "spam")
        assertTrue(said.cancelled)
        assertTrue(said.lines.isEmpty())
        assertEquals(0, sent)
    }

    @Test
    fun sentListenersHearWhatWentOut() {
        val sent = ArrayList<String>()
        chat.listen { it.recipients.removeIf { player -> player == sam } }
        chat.onSent { event -> event.chat.recipients.forEach { sent += it.toString() } }
        chat.say(alex, "hi")
        assertEquals(listOf(alex.toString(), kim.toString()), sent)
    }

    @Test
    fun aRenderingThatThrowsFallsBackToTheServersFormat() {
        chat.listen { it.rendering = ChatRendering.same { error("broken format") } }
        val said = chat.say(alex, "hi")
        assertEquals("<Alex> hi", said.seenBy(sam))
        assertEquals(1, log.errors.size)
    }

    @Test
    fun recipientsCanBeAddedOnlyWhenOnline() {
        val gone = PlayerRef.of(Uuid.random())
        chat.quit(kim)
        chat.listen {
            assertFalse(it.recipients.add(gone))
            assertFalse(it.recipients.add(alex))
            assertFalse(it.recipients.changed)
        }
        assertEquals(setOf(alex, sam), chat.say(alex, "hi").lines.keys)
    }

    @Test
    fun closingStopsListening() {
        chat.listen { it.isCancelled = true }
        chat.close()
        assertFalse(chat.say(alex, "hi").cancelled)
        assertTrue(chat.closed)
    }
}
