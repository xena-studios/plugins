package co.xenastudios.core.benchmarks

import co.xenastudios.core.common.i18n.Language
import co.xenastudios.core.common.i18n.MessageDrawer
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.jvm.text.AdventureRenderer
import co.xenastudios.core.jvm.text.BungeeRenderer
import net.kyori.adventure.text.Component
import net.md_5.bungee.api.chat.BaseComponent
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import kotlin.uuid.Uuid

/**
 * A message drawn to the server's own text for a reader's language, as every send does: to
 * Adventure (Paper, Folia) and to Spigot's components. A message with no placeholders is drawn
 * once per language and kept, so a send is a lookup; one with placeholders is rendered and
 * drawn each time.
 */
@State(Scope.Benchmark)
public open class MessageBenchmark {
    @Param("en", "de")
    public var language: String = "en"

    private lateinit var adventure: MessageDrawer<Component>
    private lateinit var spigot: MessageDrawer<Array<BaseComponent>>
    private lateinit var reader: Language
    private val player = co.xenastudios.core.api.PlayerRef.of(Uuid.random())
    private val placeholders = mapOf("amount" to Placeholder.text(250), "player" to Placeholder.text("Alex"))

    @Setup
    public fun setup() {
        val translations = translations()
        adventure = MessageDrawer(translations, FixedLanguage(language), AdventureRenderer)
        spigot = MessageDrawer(translations, FixedLanguage(language), BungeeRenderer(hex = true))
        reader = adventure.languageOf(player)
    }

    /** The reader's language from their client's locale, as each send works it out. */
    @Benchmark
    public fun languageOf(): Language = adventure.languageOf(player)

    @Benchmark
    public fun adventureKept(): Component? = adventure.draw(BenchMessages.noPermission, reader)

    @Benchmark
    public fun adventureWithPlaceholders(): Component? = adventure.draw(BenchMessages.paid, reader, placeholders)

    @Benchmark
    public fun spigotWithPlaceholders(): Array<BaseComponent>? = spigot.draw(BenchMessages.paid, reader, placeholders)
}
