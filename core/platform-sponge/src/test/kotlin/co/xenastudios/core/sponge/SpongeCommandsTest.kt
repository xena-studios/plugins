package co.xenastudios.core.sponge

import kotlin.test.Test
import kotlin.test.assertEquals

class SpongeCommandsTest {
    @Test
    fun `the dispatcher reads the label and what was typed after it`() {
        assertEquals("xsample", SpongeCommands.line("xsample", ""))
        assertEquals("xsample give Nobody 5", SpongeCommands.line("xsample", "give Nobody 5"))
        // An alias is the label the dispatcher sees, as on every platform.
        assertEquals("xs reload", SpongeCommands.line("xs", "reload"))
    }
}
