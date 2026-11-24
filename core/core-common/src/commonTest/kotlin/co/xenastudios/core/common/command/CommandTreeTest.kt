package co.xenastudios.core.common.command

import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.common.permission.has
import co.xenastudios.core.testing.FakeCommandSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class CommandTreeTest {

    private object Perms : PermissionSet("xtest") {
        val use: Permission = permission("use", "Use it.", PermissionDefault.EVERYONE)
        val skip: Permission = permission("skip", "Skip waiting.")
        val all: Permission = permission("*", "Everything.", children = grants(use, skip))
    }

    private fun fails(message: String, block: CommandTreeBuilder.() -> Unit) {
        val e = assertFailsWith<IllegalArgumentException> { commands(block) }
        assertEquals(message, e.message)
    }

    @Test
    fun theTreeIsWhatWasDeclared() {
        val tree = commands {
            root("xtest", "Test.", aliases = listOf("XT")) {
                literal("a", "A.", permission = Perms.use) {
                    val n = argument("n", ArgumentTypes.integer(), "A number.")
                    cooldown(1.minutes, bypass = Perms.skip)
                    executes { it[n] }
                }
            }
        }
        val root = tree.root("xt")!!
        assertEquals(listOf("xt"), root.aliases)
        assertFalse(root.executable)
        assertEquals("/xtest <a>", root.usage)
        val a = root.child("A")!!
        assertEquals(listOf("xtest", "a"), a.path)
        assertEquals("/xtest a <n>", a.usage)
        assertEquals(listOf(root, a), tree.nodes)
        assertEquals(listOf(Perms.use, Perms.skip), tree.permissions)
    }

    @Test
    fun badTreesFailAsTheyreDeclared() {
        fails("/xtest neither runs anything nor has subcommands") { root("xtest", "") {} }
        fails("/Xtest: \"Xtest\" isn't a command word (lower-case a-z, 0-9, - and _)") { root("Xtest", "") { executes { } } }
        fails("a root command: \"xtest\" already names /xtest") {
            root("xtest", "") { executes { } }
            root("other", "", aliases = listOf("xtest")) { executes { } }
        }
        fails("a subcommand of /xtest: \"b\" already names /xtest a") {
            root("xtest", "") {
                literal("a", "", aliases = listOf("b")) { executes { } }
                literal("b", "") { executes { } }
            }
        }
        fails("/xtest: required <b> after optional [a]") {
            root("xtest", "") {
                optional("a", ArgumentTypes.word, "")
                argument("b", ArgumentTypes.word, "")
                executes { }
            }
        }
        fails("/xtest: <a> takes the rest of the line, so nothing can follow it") {
            root("xtest", "") {
                argument("a", ArgumentTypes.text, "")
                optional("b", ArgumentTypes.word, "")
                executes { }
            }
        }
        fails("/xtest: a command takes arguments or has subcommands, not both") {
            root("xtest", "") {
                argument("a", ArgumentTypes.word, "")
                literal("b", "") { executes { } }
            }
        }
        fails("/xtest has hooks but nothing they run around") {
            root("xtest", "") {
                confirm()
                literal("b", "") { executes { } }
            }
        }
    }

    @Test
    fun permissionsAreDeclaredOnce() {
        assertEquals(listOf("xtest.use", "xtest.skip", "xtest.*"), Perms.permissions.map { it.node })
        assertEquals(mapOf("xtest.use" to true, "xtest.skip" to true), Perms.all.children)
        assertEquals(Perms.use, Perms["xtest.use"])
        val e = assertFailsWith<IllegalArgumentException> {
            object : PermissionSet("xtest") {
                val a = permission("a", "")
                val b = permission("a", "")
            }
        }
        assertEquals("permission xtest.a declared twice", e.message)
        assertFailsWith<IllegalArgumentException> { object : PermissionSet("xtest") { val a = permission("A b", "") } }
        assertFailsWith<IllegalArgumentException> { object : PermissionSet("X") {} }
    }

    @Test
    fun aSourceHasAPermissionOrNot() {
        val steve = FakeCommandSource.player("Steve", "xtest.use")
        assertTrue(steve.has(Perms.use))
        assertFalse(steve.has(Perms.skip))
        assertTrue(steve.has(null))
    }

    @Test
    fun argumentTypesReadWhatTheySay() {
        val console = FakeCommandSource.console()
        assertEquals(ArgumentError.TooLarge(10.0), ArgumentTypes.integer(max = 10).parse("11", console).errorOrNull())
        assertEquals(2.5, ArgumentTypes.number(min = 0.0).parse("2.5", console).getOrNull())
        assertEquals(ArgumentError.NotANumber, ArgumentTypes.number().parse("NaN", console).errorOrNull())
        assertEquals(true, ArgumentTypes.boolean.parse("TRUE", console).getOrNull())
        assertEquals(90.minutes, ArgumentTypes.duration().parse("1h30m", console).getOrNull())
        assertEquals(ArgumentError.TooLong(1.minutes), ArgumentTypes.duration(max = 1.minutes).parse("2m", console).errorOrNull())
        assertEquals("Notch_2", ArgumentTypes.playerName.parse("Notch_2", console).getOrNull())
        assertTrue(ArgumentTypes.playerName.parse("no way", console).isErr)
        assertEquals(null, ArgumentTypes.integer().min)
        assertEquals(1.0, ArgumentTypes.integer(min = 1).min)
    }
}
