package co.xenastudios.core.benchmarks

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.command.ArgumentTypes
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandResult
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.OnlinePlayers
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakePlatform
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State

internal object CoinPermissions : PermissionSet("xcoins") {
    val see: Permission = permission("see", "See coins.", PermissionDefault.EVERYONE)
    val give: Permission = permission("give", "Give coins.")
}

/**
 * A typed command through the core's dispatcher, as every platform runs it: the words split,
 * the tree walked with permissions checked, the arguments parsed (a player, a number, the rest
 * of the line), the handler run; and tab completion of a player's name.
 */
@State(Scope.Benchmark)
public open class CommandBenchmark {
    private val platform = FakePlatform()
    private val alex = FakeCommandSource.player("Alex", CoinPermissions.see.node)
    private val players = object : OnlinePlayers {
        val online: Map<String, PlayerRef> = mapOf("Alex" to alex.player!!, "Steve" to PlayerRef.of(kotlin.uuid.Uuid.random()))
        override fun find(name: String, viewer: CommandSource) = online.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        override fun names(viewer: CommandSource) = online.keys.toList()
    }
    private var given = 0L
    private val dispatcher = CommandDispatcher(
        commands {
            root("xcoins", "Coins.", permission = CoinPermissions.see, aliases = listOf("coins")) {
                executes { given++ }
                literal("give", "Gives coins.", permission = CoinPermissions.give) {
                    val who = argument("player", ArgumentTypes.player(players), "Who gets them.")
                    val amount = argument("amount", ArgumentTypes.integer(min = 1, max = 1000), "How many.")
                    val reason = optional("reason", ArgumentTypes.text, "Why.")
                    executes { ctx -> given += ctx[amount] + (ctx.getOrNull(reason)?.length ?: 0) + ctx[who].hashCode() % 2 }
                }
            }
        },
        platform.clock,
        platform.log,
    )
    private val steve = FakeCommandSource.player("Steve", CoinPermissions.see.node, CoinPermissions.give.node)

    @Benchmark
    public fun dispatch(): CommandResult = dispatcher.dispatch(steve, "coins give alex 25 for the build contest")

    @Benchmark
    public fun noPermission(): CommandResult = dispatcher.dispatch(alex, "xcoins give Steve 25")

    @Benchmark
    public fun suggestPlayer(): List<String> = dispatcher.suggest(steve, "xcoins give St")
}
