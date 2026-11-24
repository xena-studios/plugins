package co.xenastudios.core.common.command

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class CommandDispatcherTest {

    private object Perms : PermissionSet("xcoins") {
        val see: Permission = permission("see", "See coins.", PermissionDefault.EVERYONE)
        val give: Permission = permission("give", "Give coins.")
        val unlimited: Permission = permission("unlimited", "No cooldown on /xcoins pay.")
    }

    private val platform = FakePlatform()
    private val online = mutableMapOf<String, PlayerRef>()
    private val players = object : OnlinePlayers {
        override fun find(name: String, viewer: CommandSource) = online.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        override fun names(viewer: CommandSource) = online.keys.toList()
    }
    private val ran = mutableListOf<String>()

    private val tree = commands {
        root("xcoins", "Coins.", permission = Perms.see, aliases = listOf("coins")) {
            executes { ran += "balance" }
            literal("give", "Gives coins.", permission = Perms.give, aliases = listOf("add")) {
                val who = argument("player", ArgumentTypes.player(players), "Who gets them.")
                val amount = argument("amount", ArgumentTypes.integer(min = 1, max = 1000), "How many.")
                val reason = optional("reason", ArgumentTypes.text, "Why.")
                executes { ctx -> ran += "give ${ctx[who]} ${ctx[amount]} ${ctx.getOrNull(reason)}" }
            }
            literal("pay", "Pays a player.") {
                playerOnly()
                val amount = argument("amount", ArgumentTypes.integer(min = 1), "How many.")
                cooldown(1.minutes, bypass = Perms.unlimited)
                executes { ctx ->
                    if (ctx[amount] > 100) ctx.fail(Component.text("Not that many."))
                    ran += "pay ${ctx[amount]}"
                }
            }
            literal("reset", "Resets every balance.") {
                confirm(30.seconds)
                executes { ran += "reset" }
            }
            literal("mode", "Sets the mode.") {
                val mode = argument("mode", ArgumentTypes.choice("Fast", "slow"), "Which.")
                executes { ctx -> ran += "mode ${ctx[mode]}" }
            }
            literal("boom", "Throws.") {
                executes { error("kaboom") }
            }
        }
    }
    private val dispatcher = CommandDispatcher(tree, platform.clock, platform.log)

    private val steve = FakeCommandSource.player("Steve", Perms.see.node, Perms.give.node)
    private val alex = FakeCommandSource.player("Alex", Perms.see.node)
    private val console = FakeCommandSource.console()

    @Test
    fun runsTheDeclaredTreeByNameAndAlias() {
        online["Alex"] = alex.player!!
        assertEquals(CommandResult.Ran, dispatcher.dispatch(steve, "xcoins"))
        assertEquals(CommandResult.Ran, dispatcher.dispatch(steve, "/COINS add alex 5 for being   nice "))
        assertEquals(CommandResult.Ran, dispatcher.dispatch(steve, "xcoins:xcoins give Alex 7"))
        assertEquals(listOf("balance", "give ${alex.player} 5 for being   nice", "give ${alex.player} 7 null"), ran)
    }

    @Test
    fun checksEveryPermissionOnThePath() {
        assertEquals(CommandResult.NoPermission("xcoins.give"), dispatcher.dispatch(alex, "xcoins give Steve 5"))
        val nobody = FakeCommandSource.player("Nobody")
        assertEquals(CommandResult.NoPermission("xcoins.see"), dispatcher.dispatch(nobody, "xcoins pay 5"))
        assertEquals(CommandResult.Unknown, dispatcher.dispatch(steve, "other"))
        assertTrue(ran.isEmpty())
    }

    @Test
    fun saysHowToTypeIt() {
        assertEquals(CommandResult.Usage("/xcoins give <player> <amount> [reason]"), dispatcher.dispatch(steve, "xcoins give"))
        assertEquals(CommandResult.Usage("/xcoins pay <amount>"), dispatcher.dispatch(steve, "xcoins pay 5 6"))
        online["Alex"] = alex.player!!
        val invalid = dispatcher.dispatch(steve, "xcoins give Alex 0")
        assertEquals(CommandResult.InvalidArgument("amount", "0", ArgumentError.TooSmall(1.0), "/xcoins give <player> <amount> [reason]"), invalid)
        assertIs<CommandResult.InvalidArgument>(dispatcher.dispatch(steve, "xcoins give Herobrine 5")).also { assertEquals(ArgumentError.UnknownPlayer, it.error) }
        assertIs<CommandResult.InvalidArgument>(dispatcher.dispatch(steve, "xcoins mode medium")).also { assertEquals(ArgumentError.NotAChoice(listOf("Fast", "slow")), it.error) }
        assertEquals(CommandResult.Ran, dispatcher.dispatch(steve, "xcoins mode FAST"))
        assertEquals(listOf("mode Fast"), ran)
    }

    @Test
    fun aParentOnlyListsTheSubcommandsTheSourceMayUse() {
        val parentOnly = CommandDispatcher(
            commands {
                root("xtools", "Tools.") {
                    literal("give", "Gives.", permission = Perms.give) { executes { } }
                    literal("list", "Lists.") { executes { } }
                }
            },
            platform.clock, platform.log,
        )
        assertEquals(CommandResult.Usage("/xtools <list>"), parentOnly.dispatch(alex, "xtools"))
        assertEquals(CommandResult.Usage("/xtools <give|list>"), parentOnly.dispatch(steve, "xtools nope"))
    }

    @Test
    fun onlyPlayersRunPlayerOnlyCommands() {
        assertEquals(CommandResult.PlayersOnly, dispatcher.dispatch(console, "xcoins pay 5"))
        assertEquals(CommandResult.Ran, dispatcher.dispatch(steve, "xcoins pay 5"))
    }

    @Test
    fun aCooldownStartsOnlyAfterASuccessfulRunAndCanBeBypassed() {
        assertEquals(CommandResult.Failed(Component.text("Not that many.")), dispatcher.dispatch(steve, "xcoins pay 500"))
        assertEquals(CommandResult.Ran, dispatcher.dispatch(steve, "xcoins pay 5"))
        platform.scheduler.advance(20.seconds)
        assertEquals(CommandResult.OnCooldown(40.seconds), dispatcher.dispatch(steve, "xcoins pay 5"))
        // Someone else's cooldown is their own.
        assertEquals(CommandResult.Ran, dispatcher.dispatch(alex, "xcoins pay 5"))
        steve.permissions += Perms.unlimited.node
        assertEquals(CommandResult.Ran, dispatcher.dispatch(steve, "xcoins pay 6"))
        steve.permissions -= Perms.unlimited.node
        platform.scheduler.advance(40.seconds)
        assertEquals(CommandResult.Ran, dispatcher.dispatch(steve, "xcoins pay 7"))
        assertEquals(listOf("pay 5", "pay 5", "pay 6", "pay 7"), ran)
    }

    @Test
    fun aConfirmationNeedsTheSameLineAgainInTime() {
        assertEquals(CommandResult.ConfirmNeeded("xcoins reset", 30.seconds), dispatcher.dispatch(steve, "/xcoins reset"))
        assertEquals(CommandResult.Ran, dispatcher.dispatch(steve, "xcoins  reset"))
        assertEquals(listOf("reset"), ran)
        assertIs<CommandResult.ConfirmNeeded>(dispatcher.dispatch(steve, "xcoins reset"))
        platform.scheduler.advance(31.seconds)
        assertIs<CommandResult.ConfirmNeeded>(dispatcher.dispatch(steve, "xcoins reset"))
        // Another source confirming doesn't count.
        assertIs<CommandResult.ConfirmNeeded>(dispatcher.dispatch(alex, "xcoins reset"))
        assertEquals(listOf("reset"), ran)
    }

    @Test
    fun aHandlerThatThrowsIsLoggedAndContained() {
        val result = dispatcher.dispatch(steve, "xcoins boom")
        assertIs<CommandResult.Error>(result)
        assertEquals("kaboom", result.cause.message)
        assertEquals(listOf("/xcoins boom failed for Steve"), platform.log.errors)
        assertEquals("Something went wrong running that command; the console has the details.", CommandFeedback.ENGLISH.message(result)?.plain())
    }

    @Test
    fun suggestsWhatTheSourceMayType() {
        online["Alex"] = alex.player!!
        online["Ann"] = steve.player!!
        assertEquals(listOf("xcoins"), dispatcher.suggest(steve, "xc"))
        assertEquals(listOf("give", "pay", "reset", "mode", "boom"), dispatcher.suggest(steve, "xcoins "))
        assertEquals(listOf("pay", "reset", "mode", "boom"), dispatcher.suggest(alex, "xcoins "))
        assertEquals(listOf("give"), dispatcher.suggest(steve, "xcoins G"))
        assertEquals(listOf("Alex", "Ann"), dispatcher.suggest(steve, "xcoins give a"))
        assertEquals(listOf("Alex", "Ann"), dispatcher.suggest(steve, "coins add "))
        assertEquals(emptyList(), dispatcher.suggest(alex, "xcoins give "))
        assertEquals(listOf("Fast", "slow"), dispatcher.suggest(steve, "xcoins mode "))
        assertEquals(emptyList(), dispatcher.suggest(steve, "xcoins give Alex 5 some reason "))
        assertEquals(emptyList(), dispatcher.suggest(console, "xcoins pay "))
        assertEquals(emptyList(), dispatcher.suggest(FakeCommandSource.player("Nobody"), "xcoins "))
    }

    @Test
    fun theEnglishFeedbackSaysWhatWentWrong() {
        fun text(result: CommandResult) = CommandFeedback.ENGLISH.message(result)?.plain()
        assertEquals(null, text(CommandResult.Ran))
        assertEquals("You can use that again in 40s.", text(CommandResult.OnCooldown(40.seconds)))
        assertEquals("\"x\" isn't a whole number. Usage: /a <n>", text(CommandResult.InvalidArgument("n", "x", ArgumentError.NotAnInteger, "/a <n>")))
        assertEquals("0 is less than 1. Usage: /a <n>", text(CommandResult.InvalidArgument("n", "0", ArgumentError.TooSmall(1.0), "/a <n>")))
        assertEquals("Type /xcoins reset again within 30s to confirm.", text(CommandResult.ConfirmNeeded("xcoins reset", 30.seconds)))
    }
}
