package co.xenastudios.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BbCodeTest {
    @Test
    fun `turns a changelog section into BBCode`() {
        val markdown = """
            ### Fixed

            - `/xplots reload` from the console now runs while Paper has
              paused the server.
            - The reply **says so** now, see [the docs](https://plugins.xenastudios.co/xplots/).

            ### Changed

            One *small* change, with `**not bold**` in code.
        """.trimIndent()

        assertEquals(
            """
            [b]Fixed[/b]

            [list]
            [*]/xplots reload from the console now runs while Paper has paused the server.
            [*]The reply [b]says so[/b] now, see [url=https://plugins.xenastudios.co/xplots/]the docs[/url].
            [/list]

            [b]Changed[/b]

            One [i]small[/i] change, with **not bold** in code.
            """.trimIndent(),
            BbCode.fromMarkdown(markdown),
        )
    }

    @Test
    fun `leaves an asterisk in a word alone`() {
        assertEquals("rent * 2 and a*b*c", BbCode.fromMarkdown("rent * 2 and a*b*c"))
    }
}
