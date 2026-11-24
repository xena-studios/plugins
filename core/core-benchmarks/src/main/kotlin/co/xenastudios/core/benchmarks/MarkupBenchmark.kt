package co.xenastudios.core.benchmarks

import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Markup
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.core.common.text.Theme
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State

/**
 * Our markup: parsing a message, which happens once per language as files load, and rendering
 * it with a send's placeholders, which happens on every send that has some.
 */
@State(Scope.Benchmark)
public open class MarkupBenchmark {
    private val tags = Theme.DEFAULT.tags
    private val source = "<prefix>Paid <highlight><amount></highlight> to <click:suggest_command:'/pay <player> '>" +
        "<hover:show_text:'<gray>Pay again'><player></hover></click>. <gradient:#B48CFF:#5EE08A>Thanks!</gradient>"
    private val parsed = Markup.parse(source, tags)
    private val placeholders = mapOf("amount" to Placeholder.text(250), "player" to Placeholder.text("Alex"))

    @Benchmark
    public fun parse(): Markup = Markup.parse(source, tags)

    @Benchmark
    public fun renderWithPlaceholders(): Component = parsed.render(placeholders)

    /** No placeholders: made once, then kept. */
    @Benchmark
    public fun renderPlain(): Component = parsed.render()
}
