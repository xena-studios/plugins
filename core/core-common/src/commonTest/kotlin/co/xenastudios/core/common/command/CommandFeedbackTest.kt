package co.xenastudios.core.common.command

import co.xenastudios.core.common.admin.AdminText
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.LanguageTag
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.testing.FakeCommandSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Command results drawn from the lang files, in the reader's language: [CommandFeedback.of]. */
class CommandFeedbackTest {
    private object Bundle : MessageBundle()

    private val catalog = MessageCatalog.of(CoreMessages, Bundle)
    private val german = mapOf(
        CoreMessages.commandNoPermission.key to "<prefix><error>Dafür hast du keine Berechtigung.",
        CoreMessages.commandUnknownPlayer.key to "<prefix><error>Kein Spieler namens <highlight><player></highlight> ist online.",
        CoreMessages.commandCooldown.key to "<prefix><error>Du kannst das in <time> wieder benutzen.",
    )
    private val translations = Translations.of(catalog, mapOf(LanguageTag.of("de") to german))
    private val steve = FakeCommandSource.player("Steve")
    private val hans = FakeCommandSource.player("Hans")
    private val feedback = CommandFeedback.of(AdminText.of(translations) { source -> if (source === hans) "de_de" else "en_us" })

    @Test
    fun eachResultIsAMessageInTheReadersLanguage() {
        assertEquals("» You don't have permission to do that.", feedback.message(CommandResult.NoPermission("x.y"), steve)?.plain())
        assertEquals("» Dafür hast du keine Berechtigung.", feedback.message(CommandResult.NoPermission("x.y"), hans)?.plain())
        val unknown = CommandResult.InvalidArgument("player", "Nobody", ArgumentError.UnknownPlayer, "/heal [player]")
        assertEquals("» No player called Nobody is online.", feedback.message(unknown, steve)?.plain())
        assertEquals("» Kein Spieler namens Nobody ist online.", feedback.message(unknown, hans)?.plain())
        assertEquals("» Du kannst das in 10 Sekunden wieder benutzen.", feedback.message(CommandResult.OnCooldown(10.seconds), hans)?.plain())
        val big = CommandResult.InvalidArgument("yaw", "400", ArgumentError.TooLarge(360.0), "/setrotation <yaw> <pitch>")
        assertEquals("» 400 is more than 360. Usage: /setrotation <yaw> <pitch>", feedback.message(big, steve)?.plain())
        assertEquals("» Usage: /fly [player]", feedback.message(CommandResult.Usage("/fly [player]"), steve)?.plain())
        assertEquals(null, feedback.message(CommandResult.Ran, steve))
    }

    @Test
    fun withoutASourceItsEnglish() {
        assertEquals("Only players can use this command.", feedback.message(CommandResult.PlayersOnly)?.plain())
    }

    private class Owned : PermissionSet("xtest") {
        val fly = node("essentials.fly", "Fly.", PermissionDefault.EVERYONE)
    }

    @Test
    fun aPermissionSetCanDeclareANodeOfAnyName() {
        val set = Owned()
        assertEquals("essentials.fly", set.fly.node)
        assertTrue(PermissionSet.isNode("essentials.fly"))
        assertFalse(PermissionSet.isNode("Has Spaces"))
        assertFailsWith<IllegalArgumentException> {
            object : PermissionSet("xtest") {
                init {
                    node("not a node", "")
                }
            }
        }
    }
}
