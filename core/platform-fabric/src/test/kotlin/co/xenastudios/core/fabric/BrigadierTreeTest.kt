package co.xenastudios.core.fabric

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.command.ArgumentTypes
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakePlatform
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.exceptions.CommandSyntaxException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.mojang.brigadier.CommandDispatcher as Brigadier

// The tree on plain Brigadier, with the core's fake source standing in for Minecraft's.
class BrigadierTreeTest {
    private object Perms : PermissionSet("xcoins") {
        val give: Permission = permission("give", "Give coins.", PermissionDefault.OP)
    }

    private val platform = FakePlatform()
    private val ran = mutableListOf<String>()
    private val players = object : OnlinePlayers {
        override fun find(name: String, viewer: CommandSource): PlayerRef? = null
        override fun names(viewer: CommandSource): List<String> = listOf("Alex", "Steve")
    }

    private val dispatcher = CommandDispatcher(
        commands {
            root("xcoins", "Coins.", aliases = listOf("coins")) {
                executes { ran += "balance" }
                literal("give", "Gives coins.", permission = Perms.give) {
                    val who = argument("player", ArgumentTypes.playerName, "Who.")
                    val amount = argument("amount", ArgumentTypes.integer(min = 1), "How many.")
                    val reason = optional("reason", ArgumentTypes.text, "Why.")
                    executes { ctx -> ran += "give ${ctx[who]} ${ctx[amount]} ${ctx.getOrNull(reason)}" }
                }
                literal("mode", "Sets the mode.") {
                    val mode = argument("mode", ArgumentTypes.choice("fast", "slow"), "Which.")
                    executes { ctx -> ran += "mode ${ctx[mode]}" }
                }
                literal("pick", "Picks a player.") {
                    argument("player", ArgumentTypes.player(players), "Who.")
                    executes { ran += "pick" }
                }
            }
        },
        platform.clock,
        platform.log,
    )

    private val tree = BrigadierTree<FakeCommandSource>({ it }, { dispatcher })
    private val brigadier = Brigadier<FakeCommandSource>().apply {
        for (label in listOf("xcoins", "coins")) root.addChild(tree.build(dispatcher.tree.roots.single(), label))
    }

    private val console = FakeCommandSource.console()
    private val steve = FakeCommandSource.player("Steve")

    private fun run(source: FakeCommandSource, line: String): Int = brigadier.execute(line, source)

    private fun suggest(source: FakeCommandSource, line: String): List<String> =
        brigadier.getCompletionSuggestions(brigadier.parse(line, source)).get().list.map { it.text }

    @Test
    fun `a line runs through our dispatcher, arguments read by our parsers`() {
        assertEquals(1, run(console, "xcoins give Alex 5 for being kind"))
        assertEquals(1, run(console, "coins"))
        assertEquals(listOf("give Alex 5 for being kind", "balance"), ran)
    }

    @Test
    fun `our errors and usage reach the source, not Brigadier's`() {
        assertEquals(0, run(console, "xcoins give Alex lots"))
        assertEquals(0, run(console, "xcoins give Alex"))
        assertEquals(0, run(console, "xcoins mode fast extra words"))
        assertEquals(
            listOf(
                "\"lots\" isn't a whole number. Usage: /xcoins give <player> <amount> [reason]",
                "Usage: /xcoins give <player> <amount> [reason]",
                "Usage: /xcoins mode <mode>",
            ),
            console.replies,
        )
        assertEquals(emptyList(), ran)
    }

    @Test
    fun `a source without the permission doesn't see the command`() {
        // Minecraft sends each player only the nodes they can use, so clients don't see it either.
        val give = brigadier.root.getChild("xcoins").getChild("give")
        assertFalse(give.canUse(steve))
        assertFailsWith<CommandSyntaxException> { run(steve, "xcoins give Alex 5") }
        steve.permissions += Perms.give.node
        assertTrue(give.canUse(steve))
        assertEquals(1, run(steve, "xcoins give Alex 5"))
    }

    @Test
    fun `suggestions are ours, for the word being typed`() {
        assertEquals(listOf("fast"), suggest(console, "xcoins mode f"))
        assertEquals(listOf("Alex", "Steve"), suggest(console, "xcoins pick "))
        assertEquals(listOf("Steve"), suggest(console, "coins pick St"))
    }

    @Test
    fun `run through execute, the line starts at our command`() {
        brigadier.register(LiteralArgumentBuilder.literal<FakeCommandSource>("execute").then(LiteralArgumentBuilder.literal<FakeCommandSource>("run").redirect(brigadier.root)))
        assertEquals(1, run(console, "execute run xcoins mode slow"))
        assertEquals(listOf("mode slow"), ran)
    }
}
