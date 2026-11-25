package co.xenastudios.core.benchmarks

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.common.chat.ChatBridge
import co.xenastudios.core.common.chat.ChatDrawing
import co.xenastudios.core.common.chat.ChatEvent
import co.xenastudios.core.common.chat.ChatRecipients
import co.xenastudios.core.common.chat.ChatRendering
import co.xenastudios.core.common.chat.RecipientAction
import co.xenastudios.core.common.chat.RecipientFilter
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.i18n.MessageDrawer
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Markup
import co.xenastudios.core.common.text.Style
import co.xenastudios.core.jvm.text.AdventureRenderer
import co.xenastudios.core.testing.RecordingLog
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import kotlin.uuid.Uuid
import net.kyori.adventure.text.Component as AdventureComponent

/**
 * One chat message through the chat bridge, as Paper's path runs it: the event, a listener that
 * sets a format, then the line drawn to Adventure and handed to each recipient. At 10 and 100
 * recipients, so the bytes per message show nothing is made per recipient (both must be the
 * same), and the time shows what a message costs against ROADMAP.md's 0.05 ms a tick.
 *
 * - `unheard`: a message no listener hears, which the platform checks first.
 * - `sameForEveryone`: one line for all, drawn once (a prefix format).
 * - `byLanguage`: one line per language, half the recipients German, half English.
 */
@State(Scope.Benchmark)
public open class ChatBenchmark {
    @Param("10", "100")
    public var recipients: Int = 10

    private val log = RecordingLog()
    private val unheard = BenchChat(log)
    private val same = BenchChat(log)
    private val grouped = BenchChat(log)
    private lateinit var players: Array<PlayerRef>
    private val sender = PlayerRef.of(Uuid.random())

    @Setup
    public fun setup() {
        players = Array(recipients) { PlayerRef.of(Uuid.random()) }
        val german = players.filterIndexed { i, _ -> i % 2 == 0 }.toSet()
        val drawer = MessageDrawer(translations(), object : LanguageSource {
            override fun clientLocale(player: PlayerRef): String = if (player in german) "de_de" else "en_us"
        }, AdventureRenderer)
        val prefix = Markup.parse("<gray>[Member]</gray> ").render()
        val hover = Style(hover = Component.text("Member"))
        val format = ChatRendering.same { chat -> Component(children = listOf(prefix, Component(chat.senderName, hover), Component.text(": " + chat.message))) }
        same.listen { it.rendering = format }
        val byLanguage = ChatRendering.byLanguage(drawer) { chat, language ->
            Component(children = listOf(Component.text(if (language.tag.toString() == "de") "[Mitglied] " else "[Member] "), Component.text(chat.senderName + ": " + chat.message)))
        }
        grouped.listen { it.rendering = byLanguage }
    }

    @Benchmark
    public fun unheard(blackhole: Blackhole) {
        blackhole.consume(unheard.say(sender, players, blackhole))
    }

    @Benchmark
    public fun sameForEveryone(blackhole: Blackhole) {
        blackhole.consume(same.say(sender, players, blackhole))
    }

    @Benchmark
    public fun byLanguage(blackhole: Blackhole) {
        blackhole.consume(grouped.say(sender, players, blackhole))
    }

    /** A platform's side, as PaperChat runs it: the event over the server's own set, then a draw per group. */
    private class BenchChat(log: RecordingLog) : ChatBridge(EventPriority.NORMAL, log) {
        fun say(sender: PlayerRef, players: Array<PlayerRef>, out: Blackhole): Int {
            if (!heard()) return 0
            val chat = fire(ChatEvent(sender, "Alex", "hello there", ArrayRecipients(players), async = true))
            val rendering = chat.rendering ?: return 0
            val drawing = ChatDrawing<AdventureComponent>(chat, rendering, AdventureRenderer)
            // The send itself is the server's: here, the line each recipient would get.
            for (player in players) out.consume(drawing.of(player))
            out.consume(drawing.of(null))
            fireSent(chat)
            return drawing.draws
        }

        override fun unregister() {}
    }

    /** A view of a set the server already has, as Paper's viewers and Bukkit's recipients are: nothing copied. */
    private class ArrayRecipients(private val players: Array<PlayerRef>) : ChatRecipients {
        override val size: Int get() = players.size
        override fun contains(player: PlayerRef): Boolean = player in players
        override fun add(player: PlayerRef): Boolean = false
        override fun remove(player: PlayerRef): Boolean = false
        override fun removeIf(filter: RecipientFilter): Int = 0
        override fun forEach(action: RecipientAction) {
            for (player in players) action.accept(player)
        }
        override val changed: Boolean get() = false
    }
}
