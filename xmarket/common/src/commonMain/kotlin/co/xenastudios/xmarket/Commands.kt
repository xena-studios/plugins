package co.xenastudios.xmarket

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.Argument
import co.xenastudios.core.common.command.ArgumentError
import co.xenastudios.core.common.command.ArgumentType
import co.xenastudios.core.common.command.ArgumentTypes
import co.xenastudios.core.common.command.CommandBuilder
import co.xenastudios.core.common.command.CommandContext
import co.xenastudios.core.common.command.CommandHandler
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.CommandTree
import co.xenastudios.core.common.command.CommandTreeBuilder
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.xmarket.config.CommandSettings
import co.xenastudios.xmarket.config.XMarketConfig
import kotlin.time.Duration.Companion.seconds

/** xMarket's permissions: the staff ones, and every node the config names, declared with its default. Built again on every reload. */
public class XMarketPermissions internal constructor(private val admin: PermissionSet) : PermissionSet(XMarketPlugin.PLUGIN_ID) {
    public val history: Permission = permission("history", "See your own trades with /shop history.", PermissionDefault.EVERYONE)
    public val historyOthers: Permission = permission("history.others", "See anyone's trades with /shop history <player>.")
    public val stats: Permission = permission("stats", "See what the shop added to or took from the economy, with /shop stats.")
    public val audit: Permission = permission("audit", "List items without a price, and buy-craft-sell loops, with /shop audit.")
    public val edit: Permission = permission("edit", "Add, price, move and remove the shop's items in game, with /shop edit.")

    internal fun of(node: String, description: String, default: PermissionDefault = PermissionDefault.EVERYONE): Permission? {
        if (node.isEmpty()) return null
        admin[node]?.let { return it }
        return this[node] ?: node(node, description, default)
    }
}

/** Builds the command tree from the config, and runs each command (in [Actions], made the first time one runs). */
internal class Commands(private val plugin: XMarketPlugin) {
    lateinit var permissions: XMarketPermissions
        private set

    var warnings: List<String> = emptyList()
        private set

    private class Building(val config: Config, val perms: XMarketPermissions, val problems: MutableList<String>, val taken: MutableSet<String>)

    private var building: Building? = null

    fun tree(config: Config): CommandTree {
        val perms = XMarketPermissions(plugin.admin.permissions)
        val problems = ArrayList<String>()
        val taken = HashSet<String>()
        building = Building(config, perms, problems, taken)
        val c = XMarketConfig.commands
        val tree = commands {
            taken += XMarketPlugin.PLUGIN_ID
            root(XMarketPlugin.PLUGIN_ID, "Shows xMarket's version, and holds its admin subcommands.") {
                executes(Run(INFO))
                literal("info", "Shows xMarket's version.") { executes(Run(INFO)) }
                mount(plugin.admin)
            }
            add(c.shop, "Opens the shop or a category; staff: buy, history, stats, audit and edit.") {
                val what = optional("what", Words(plugin, Words.SHOP), "A category, or buy, history, stats, audit or edit.")
                executes(Run(SHOP, what, optional("more", ArgumentTypes.text, "What buy, history, stats or edit take.")))
            }
            add(c.sell, "Sells the item in your hand, everything that sells, or one item.") {
                playerOnly()
                val what = argument("what", Words(plugin, Words.SELL), "hand, all, or an item: diamond.")
                if (config[XMarketConfig.sell.confirmAll]) hook(ConfirmAll())
                executes(Run(SELL, what, optional("amount", ArgumentTypes.integer(1, Trades.MAX_AMOUNT), "At most how many.")))
            }
            add(c.sellgui, "Opens a chest to drop items into; they're sold as it closes.") {
                playerOnly()
                executes(Run(SELLGUI))
            }
            add(c.worth, "What the item in your hand, or one named, sells for.") {
                executes(Run(WORTH, optional("item", Words(plugin, Words.PRICED), "An item, as Minecraft names it; the one in your hand without one.")))
            }
        }
        building = null
        permissions = perms
        warnings = problems
        return tree
    }

    private inline fun CommandTreeBuilder.add(section: CommandSettings, description: String, block: CommandBuilder.() -> Unit) {
        val b = building!!
        if (!b.config[section.enabled]) return
        val name = section.command
        if (name in b.taken) {
            b.problems += "/$name is also another of xMarket's commands' alias, so it's skipped. Remove that alias."
            return
        }
        val aliases = b.config[section.aliases].filter { it != name }.distinct().filter { alias ->
            (alias !in b.taken).also { free -> if (!free) b.problems += "${section.path}.aliases: /$alias is already another xMarket command's, so it's left out." }
        }
        b.taken += name
        b.taken += aliases
        root(name, description, b.perms.of(b.config[section.permission], "Use /$name."), aliases, block)
    }

    // /sell all asks to be typed again within 10 seconds when sell.confirm-all is on; the rest run at once.
    private class ConfirmAll : co.xenastudios.core.common.command.CommandHook {
        private val confirmation = co.xenastudios.core.common.command.Confirmation(10.seconds)

        override fun before(context: CommandContext): co.xenastudios.core.common.command.CommandResult? {
            val all = context.line.trim().split(' ').getOrNull(1).equals("all", ignoreCase = true)
            return if (all) confirmation.before(context) else null
        }
    }

    /** Every command's handler, as one class told apart by [id], rather than a lambda each. */
    private inner class Run(private val id: Int, private vararg val args: Argument<*>) : CommandHandler {
        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> arg(ctx: CommandContext, index: Int): T? = ctx.getOrNull(args[index] as Argument<T>)

        override fun run(context: CommandContext) {
            val a = actions()
            when (id) {
                INFO -> a.info(context)
                SHOP -> a.shop(context, arg(context, 0), arg<String>(context, 1)?.trim()?.split(' ')?.filter { it.isNotEmpty() }.orEmpty())
                SELL -> a.sell(context, arg<String>(context, 0)!!, arg(context, 1))
                SELLGUI -> a.sellGui(context)
                WORTH -> a.worth(context, arg(context, 0))
            }
        }
    }

    private var made: Actions? = null

    private fun actions(): Actions = made ?: Actions(plugin).also { made = it }

    private companion object {
        const val INFO = 0
        const val SHOP = 1
        const val SELL = 10
        const val SELLGUI = 11
        const val WORTH = 12
    }
}

/**
 * A word, completed from the shop: its categories, the items it sells or prices, a category's
 * entries, or what `/sell` takes. Parsing is the command's, which says what's wrong in xMarket's
 * own words.
 */
internal class Words(private val plugin: XMarketPlugin, private val kind: Int) : ArgumentType<String> {
    override val name: String get() = "word"

    override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> = Outcome.Ok(input)

    override fun suggest(source: CommandSource): List<String> = try {
        val prices = plugin.prices
        when (kind) {
            CATEGORIES -> prices.scaled.map { it.id }
            SHOP -> prices.scaled.filter { c -> c.permission == null || source.hasPermission(c.permission) }.map { it.id } +
                listOf("buy", "history").filter { source.player != null } +
                listOf(plugin.permissions.stats to "stats", plugin.permissions.audit to "audit", plugin.permissions.edit to "edit").filter { source.hasPermission(it.first.node) }.map { it.second }
            BUYABLE -> prices.all().filter { it.entry.buy != null && it.entry.item.specific == 0 && it.entry.commands.isEmpty() }.map { it.entry.item.shortId }.distinct()
            PRICED -> prices.ids.map { it.removePrefix("minecraft:") }
            SELL -> listOf("hand", "all") + prices.all().filter { it.entry.sell != null && it.entry.item.specific == 0 }.map { it.entry.item.shortId }.distinct()
            else -> prices.all().map { it.entry.id }.distinct()
        }
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        const val CATEGORIES = 0
        const val BUYABLE = 1
        const val PRICED = 2
        const val SELL = 3
        const val ENTRIES = 4
        const val SHOP = 5
    }
}
