package co.xenastudios.xmoney

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome as DbOutcome
import co.xenastudios.core.common.command.Argument
import co.xenastudios.core.common.command.ArgumentError
import co.xenastudios.core.common.command.CommandHandler
import co.xenastudios.core.common.command.ArgumentType
import co.xenastudios.core.common.command.ArgumentTypes
import co.xenastudios.core.common.command.CommandBuilder
import co.xenastudios.core.common.command.CommandContext
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.CommandTree
import co.xenastudios.core.common.command.CommandTreeBuilder
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.xmoney.api.TransactionKind
import co.xenastudios.xmoney.api.TransactionResult.Status
import co.xenastudios.xmoney.config.CommandSettings
import co.xenastudios.xmoney.config.XMoneyConfig
import co.xenastudios.xmoney.ledger.Account
import co.xenastudios.xmoney.ledger.Ledger
import co.xenastudios.xmoney.ledger.Outcome
import kotlin.time.Duration.Companion.milliseconds

/**
 * xMoney's permissions: `/xmoney status` and `import`, `/eco`'s parts, and every node the config
 * names (each command's, `balance.permission-others`), declared with its default. Built again on
 * every reload; two settings naming the same node share one.
 */
public class XMoneyPermissions internal constructor(private val admin: PermissionSet) : PermissionSet(XMoneyPlugin.PLUGIN_ID) {
    public val status: Permission = permission("status", "Use /xmoney status: where balances are kept, and whether saving keeps up.")
    public val import: Permission = permission("import", "Use /xmoney import: balances from another economy plugin.")
    public val ecoParts: Map<String, Permission> = listOf("give", "take", "set", "reset", "history").associateWith {
        permission("eco.$it", "Use /eco $it.")
    }

    internal fun of(node: String, description: String, default: PermissionDefault = PermissionDefault.OP): Permission? {
        if (node.isEmpty()) return null
        admin[node]?.let { return it }
        return this[node] ?: node(node, description, default)
    }
}

/** Builds the command tree from the config, and runs each command. */
internal class Commands(private val plugin: XMoneyPlugin) {
    /** The permissions the last tree checks. */
    lateinit var permissions: XMoneyPermissions
        private set

    /** What building the last tree found wrong: aliases that clash. */
    var warnings: List<String> = emptyList()
        private set

    private class Building(val config: Config, val perms: XMoneyPermissions, val problems: MutableList<String>, val taken: MutableSet<String>)

    private var building: Building? = null

    fun tree(config: Config): CommandTree {
        val perms = XMoneyPermissions(plugin.admin.permissions)
        val problems = ArrayList<String>()
        val taken = HashSet<String>()
        building = Building(config, perms, problems, taken)
        val c = XMoneyConfig.commands
        val tree = commands {
            taken += XMoneyPlugin.PLUGIN_ID
            root(XMoneyPlugin.PLUGIN_ID, "Shows xMoney's version, and holds its admin subcommands.") {
                executes(Run(INFO))
                literal("info", "Shows xMoney's version.") { executes(Run(INFO)) }
                literal("status", "Where balances are kept, and whether saving keeps up.", perms.status) { executes(Run(STATUS)) }
                literal("import", "Imports balances from another economy plugin: a dry run until confirmed.", perms.import) {
                    val source = argument("source", Sources(plugin), "The plugin to import from.")
                    val confirm = optional("confirm", ArgumentTypes.choice("confirm"), "Imports, rather than saying what it would do.")
                    val overwrite = optional("overwrite", ArgumentTypes.choice("overwrite"), "Imports over accounts that have changed here since.")
                    executes(Run(IMPORT, source, confirm, overwrite))
                }
                mount(plugin.admin)
            }
            add(c.balance, "Your balance, or another player's.", PermissionDefault.EVERYONE) {
                executes(Run(BALANCE, optional("player", Players(plugin), "Whose: a player who has played here.")))
            }
            perms.of(config[c.balance.permissionOthers], "See another player's balance with /balance.")
            add(c.pay, "Send money to another player.", PermissionDefault.EVERYONE) {
                playerOnly()
                val player = argument("player", Players(plugin), "Who to pay: a player who has played here.")
                val amount = argument("amount", ArgumentTypes.word, "How much: 250, 12.5 or 1.5k.")
                val confirm = optional("confirm", ArgumentTypes.choice("confirm"), "Sends a payment that asked to be confirmed.")
                executes(Run(PAY, player, amount, confirm))
            }
            add(c.baltop, "The richest players.", PermissionDefault.EVERYONE) {
                executes(Run(BALTOP, optional("page", ArgumentTypes.integer(1), "Which page.")))
            }
            add(c.eco, "Give, take, set and look up players' money.", PermissionDefault.OP) {
                for ((part, id) in listOf("give" to GIVE, "take" to TAKE, "set" to SET, "reset" to RESET)) {
                    literal(part, ECO_HELP.getValue(part), perms.ecoParts.getValue(part)) {
                        val player = argument("player", Players(plugin), "Whose balance: a player who has played here, or an account a plugin made.")
                        if (id == RESET) executes(Run(id, player))
                        else executes(Run(id, player, argument("amount", ArgumentTypes.word, "How much: 250, 12.5 or 1.5k.")))
                    }
                }
                literal("history", "Every change to a balance, newest first.", perms.ecoParts.getValue("history")) {
                    val player = argument("player", Players(plugin), "Whose history.")
                    executes(Run(HISTORY, player, optional("page", ArgumentTypes.integer(1), "Which page.")))
                }
            }
        }
        building = null
        permissions = perms
        warnings = problems
        return tree
    }

    // A root command from its config section, unless it's off or its name is taken.
    private inline fun CommandTreeBuilder.add(section: CommandSettings, description: String, default: PermissionDefault, block: CommandBuilder.() -> Unit) {
        val b = building!!
        if (!b.config[section.enabled]) return
        val name = section.command
        if (name in b.taken) {
            b.problems += "/$name is also another of xMoney's commands' alias, so it's skipped. Remove that alias."
            return
        }
        val aliases = b.config[section.aliases].filter { it != name }.distinct().filter { alias ->
            (alias !in b.taken).also { free -> if (!free) b.problems += "${section.path}.aliases: /$alias is already another xMoney command's, so it's left out." }
        }
        b.taken += name
        b.taken += aliases
        root(name, description, b.perms.of(b.config[section.permission], "Use /$name.", default), aliases, block)
    }

    /**
     * Every command's handler, as one class told apart by [id], rather than a lambda each: the JVM
     * makes a class of every lambda as it's made (decisions/2026-11-25-a-lighter-core.md).
     */
    private inner class Run(private val id: Int, private vararg val args: Argument<*>) : CommandHandler {
        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> arg(ctx: CommandContext, index: Int): T? = ctx.getOrNull(args[index] as Argument<T>)

        override fun run(context: CommandContext) {
            val a = actions()
            when (id) {
                INFO -> a.info(context)
                STATUS -> a.status(context)
                IMPORT -> Imports.run(plugin, context.source, arg<String>(context, 0)!!, arg<String>(context, 1) != null, arg<String>(context, 2) != null)
                BALANCE -> a.balance(context, arg(context, 0), plugin.config[XMoneyConfig.commands.balance.permissionOthers])
                PAY -> a.pay(context, arg<String>(context, 0)!!, arg<String>(context, 1)!!, arg<String>(context, 2) != null)
                BALTOP -> a.baltop(context, arg<Int>(context, 0) ?: 1)
                GIVE, TAKE, SET -> a.eco(context, ECO_PARTS[id - GIVE], arg<String>(context, 0)!!, arg(context, 1))
                RESET -> a.eco(context, "reset", arg<String>(context, 0)!!, null)
                HISTORY -> a.history(context, arg<String>(context, 0)!!, arg<Int>(context, 1) ?: 1)
            }
        }
    }

    // What the commands do, made the first time one runs: not needed to build the tree, so not
    // loaded as the server starts (decisions/2026-11-25-a-lighter-core.md).
    private var made: Actions? = null

    private fun actions(): Actions = made ?: Actions(plugin).also { made = it }

    private companion object {
        const val INFO = 0
        const val STATUS = 1
        const val IMPORT = 2
        const val BALANCE = 3
        const val PAY = 4
        const val BALTOP = 5
        const val GIVE = 6
        const val TAKE = 7
        const val SET = 8
        const val RESET = 9
        const val HISTORY = 10
        val ECO_PARTS = listOf("give", "take", "set")

        val ECO_HELP = mapOf(
            "give" to "Add to a balance.",
            "take" to "Take from a balance, if they have it.",
            "set" to "Set a balance.",
            "reset" to "Set a balance back to starting-balance.",
        )
    }

    /** A player's name, completed from who's online. */
    private class Players(private val plugin: XMoneyPlugin) : ArgumentType<String> {
        override val name: String get() = "player"
        override fun parse(input: String, source: CommandSource): DbOutcome<String, ArgumentError> = DbOutcome.Ok(input)
        override fun suggest(source: CommandSource): List<String> = plugin.server.onlineNames()
    }

    /** `/xmoney import`'s source, completed from the importers the platform has. */
    private class Sources(private val plugin: XMoneyPlugin) : ArgumentType<String> {
        override val name: String get() = "choice"
        override val choices: List<String> get() = plugin.sources?.ids.orEmpty()
        override fun parse(input: String, source: CommandSource): DbOutcome<String, ArgumentError> =
            choices.firstOrNull { it.equals(input, ignoreCase = true) }?.let { DbOutcome.Ok(it) } ?: DbOutcome.Err(ArgumentError.NotAChoice(choices))
    }
}

/** What each command does: [Commands] builds the tree, this runs it, made the first time a command runs. */
internal class Actions(private val plugin: XMoneyPlugin) {
    private val texts get() = plugin.texts

    // ---- replies ----

    private fun fail(ctx: CommandContext, message: MessageKey, vararg placeholders: Pair<String, Any>): Nothing =
        ctx.fail(texts.render(ctx.source.player, message, *placeholders) ?: Component.EMPTY)

    fun reply(source: CommandSource, message: MessageKey, vararg placeholders: Pair<String, Any>) = texts.reply(source, message, *placeholders)

    /** The ledger, or the reason there's none yet. */
    fun ledger(ctx: CommandContext): Ledger =
        plugin.ledger ?: fail(ctx, if (plugin.opening) XMoneyMessages.opening else XMoneyMessages.noDatabase)

    fun money(units: Long) = plugin.format(units)

    /** What was typed as an amount, in units, or why it isn't one. */
    fun amount(ctx: CommandContext, typed: String): Long {
        val parsed = plugin.compiled.format.parse(typed)
        return when (parsed.problem) {
            null -> parsed.units
            CurrencyFormat.Problem.TOO_PRECISE -> fail(ctx, XMoneyMessages.amountTooPrecise, "decimals" to plugin.compiled.format.decimals)
            CurrencyFormat.Problem.TOO_LARGE -> fail(ctx, XMoneyMessages.amountTooLarge)
            CurrencyFormat.Problem.INVALID -> fail(ctx, XMoneyMessages.amountInvalid, "amount" to typed)
        }
    }

    fun source(source: CommandSource) = if (source.player == null) "Console" else source.name

    // ---- /xmoney ----

    fun info(ctx: CommandContext) {
        val ledger = plugin.ledger
        reply(
            ctx.source, XMoneyMessages.info, "version" to plugin.version, "platform" to plugin.platformName,
            "accounts" to (ledger?.accounts?.size ?: 0), "storage" to (ledger?.description ?: "no database"),
        )
    }

    fun status(ctx: CommandContext) {
        val ledger = ledger(ctx)
        val language = texts.language(ctx.source.player)
        val shared = if (ledger.shared) texts.render(language, XMoneyMessages.statusShared) ?: Component.EMPTY else Component.EMPTY
        val problem = when {
            !ledger.available -> "${ledger.description} can't be reached" + (ledger.problem?.let { ": $it" } ?: "")
            ledger.problem != null -> ledger.problem!!
            else -> null
        }
        reply(
            ctx.source, XMoneyMessages.status, "storage" to ledger.description, "network" to Placeholder.component(shared),
            "accounts" to ledger.accounts.size, "total" to money(ledger.accounts.total()), "waiting" to ledger.waiting,
            "problem" to (problem?.let { Placeholder.text(it) } ?: Placeholder.component(texts.render(language, XMoneyMessages.statusNone) ?: Component.EMPTY)),
        )
    }

    // ---- /balance ----

    fun balance(ctx: CommandContext, name: String?, others: String) {
        val source = ctx.source
        val ledger = ledger(ctx)
        val self = source.player
        if (name == null || (self != null && name.equals(source.name, ignoreCase = true))) {
            if (self == null) fail(ctx, CoreMessages.commandPlayersOnly)
            reply(source, XMoneyMessages.balanceOwn, "balance" to money(plugin.balanceOf(self)))
            return
        }
        if (others.isNotEmpty() && !source.hasPermission(others)) fail(ctx, CoreMessages.commandNoPermission)
        find(ledger, name, playersOnly = false).onSettled { account, _ ->
            if (account == null) reply(source, XMoneyMessages.balanceUnknown, "player" to name)
            else reply(source, XMoneyMessages.balanceOther, "player" to account.name, "balance" to money(account.balance))
        }
    }

    // ---- /pay ----

    fun pay(ctx: CommandContext, name: String, typed: String, confirmed: Boolean) {
        val source = ctx.source
        val ledger = ledger(ctx)
        val payer = ctx.player
        val pay = XMoneyConfig.commands.pay
        val amount = amount(ctx, typed)
        if (name.equals(source.name, ignoreCase = true)) fail(ctx, XMoneyMessages.paySelf)
        val decimals = plugin.compiled.format.decimals
        val minimum = maxOf(1, XMoneyPlugin.roundTo(plugin.config[pay.minimum].units, decimals))
        if (amount < minimum) fail(ctx, XMoneyMessages.payTooLittle, "minimum" to money(minimum))
        val own = ledger.accounts[payer]
        if (own == null || own.balance < amount) {
            fail(ctx, XMoneyMessages.payNotEnough, "amount" to money(amount), "balance" to money(own?.balance ?: 0))
        }
        find(ledger, name, playersOnly = true).onSettled { payee, _ ->
            when {
                payee == null -> reply(source, XMoneyMessages.balanceUnknown, "player" to name)
                payee.id == payer -> reply(source, XMoneyMessages.paySelf)
                else -> {
                    val confirmAbove = XMoneyPlugin.roundTo(plugin.config[pay.confirmAbove].units, decimals)
                    if (confirmAbove > 0 && amount >= confirmAbove && !confirmed) {
                        val command = "/${pay.command} ${payee.name} ${plugin.compiled.format.typed(amount)} confirm"
                        reply(source, XMoneyMessages.payConfirm, "amount" to money(amount), "player" to payee.name, "command" to command)
                        return@onSettled
                    }
                    ledger.transfer(own, payee, amount, plugin.cause(TransactionKind.PAY, source.name, "")).onSettled { outcome, _ ->
                        paid(source, payer, payee, amount, outcome ?: Outcome(Status.UNAVAILABLE, 0, own.balance))
                    }
                }
            }
        }
    }

    fun paid(source: CommandSource, payer: PlayerRef, payee: Account, amount: Long, outcome: Outcome) {
        when (outcome.status) {
            Status.SUCCESS -> {
                reply(source, XMoneyMessages.paySent, "amount" to money(amount), "player" to payee.name, "balance" to money(outcome.balance))
                if (plugin.server.isOnline(payee.id)) {
                    texts.render(payee.id, XMoneyMessages.payReceived, "amount" to money(amount), "player" to source.name)?.let { plugin.server.send(payee.id, it) }
                }
            }
            Status.INSUFFICIENT_FUNDS -> reply(source, XMoneyMessages.payNotEnough, "amount" to money(amount), "balance" to money(outcome.balance))
            Status.BALANCE_LIMIT -> reply(source, XMoneyMessages.payLimit, "player" to payee.name)
            Status.SAME_ACCOUNT -> reply(source, XMoneyMessages.paySelf)
            Status.CANCELLED -> reply(source, XMoneyMessages.payCancelled)
            Status.UNAVAILABLE -> reply(source, XMoneyMessages.payUnavailable)
            Status.INVALID_AMOUNT -> reply(source, XMoneyMessages.amountInvalid, "amount" to money(amount))
        }
    }

    // ---- /baltop ----

    fun baltop(ctx: CommandContext, page: Int) {
        val source = ctx.source
        val ledger = ledger(ctx)
        val accounts = ledger.accounts
        val perPage = plugin.config[XMoneyConfig.commands.baltop.perPage]
        val ranked = accounts.ranked
        if (ranked == 0) {
            reply(source, XMoneyMessages.baltopEmpty)
            return
        }
        val pages = pages(ranked.toLong(), perPage)
        val number = page.coerceIn(1, pages)
        reply(source, XMoneyMessages.baltopHeader, "total" to money(accounts.total()), "count" to ranked)
        for (row in accounts.top((number - 1) * perPage + 1, perPage)) {
            reply(source, XMoneyMessages.baltopLine, "rank" to row.rank, "player" to row.account.name, "balance" to money(row.balance))
        }
        footer(source, XMoneyMessages.baltopFooter, number, pages, "/${XMoneyConfig.commands.baltop.command}")
    }

    fun footer(source: CommandSource, message: MessageKey, page: Int, pages: Int, command: String) {
        if (pages <= 1) return
        reply(
            source, message, "page" to page, "pages" to pages, "next" to "$command ${page + 1}", "previous" to "$command ${page - 1}",
            "has_next" to Placeholder.section(page < pages), "has_previous" to Placeholder.section(page > 1),
        )
    }

    fun pages(total: Long, per: Int): Int = maxOf(1, ((total + per - 1) / per).toInt())

    // ---- /eco ----

    fun eco(ctx: CommandContext, part: String, name: String, typed: String?) {
        val source = ctx.source
        val ledger = ledger(ctx)
        val amount = typed?.let { amount(ctx, it) }
        if (amount == 0L && part != "set") fail(ctx, CoreMessages.commandUsage, "usage" to "/${XMoneyConfig.commands.eco.command} $part <player> <amount>")
        val kind = when (part) {
            "give" -> TransactionKind.GIVE
            "take" -> TransactionKind.TAKE
            else -> TransactionKind.SET
        }
        val cause = plugin.cause(kind, source(source), "")
        find(ledger, name, playersOnly = false).onSettled { account, _ ->
            if (account == null) {
                reply(source, XMoneyMessages.balanceUnknown, "player" to name)
                return@onSettled
            }
            val change = when (part) {
                "give" -> ledger.deposit(account, amount!!, cause)
                "take" -> ledger.withdraw(account, amount!!, cause)
                "set" -> ledger.set(account, amount!!, cause)
                else -> ledger.set(account, plugin.compiled.start, cause)
            }
            change.onSettled { result, _ ->
                val outcome = result ?: Outcome(Status.UNAVAILABLE, 0, account.balance)
                val player = "player" to account.name
                val balance = "balance" to money(outcome.balance)
                when (outcome.status) {
                    Status.SUCCESS -> reply(
                        source,
                        when (part) {
                            "give" -> XMoneyMessages.ecoGiven
                            "take" -> XMoneyMessages.ecoTaken
                            "set" -> XMoneyMessages.ecoSet
                            else -> XMoneyMessages.ecoReset
                        },
                        player, balance, "amount" to money(outcome.amount),
                    )
                    Status.INSUFFICIENT_FUNDS -> reply(source, XMoneyMessages.ecoNotEnough, player, balance)
                    Status.BALANCE_LIMIT -> reply(source, XMoneyMessages.ecoLimit, player)
                    Status.CANCELLED -> reply(source, XMoneyMessages.ecoCancelled)
                    Status.UNAVAILABLE -> reply(source, XMoneyMessages.ecoUnavailable)
                    else -> reply(source, CoreMessages.commandError)
                }
            }
        }
    }

    fun history(ctx: CommandContext, name: String, page: Int) {
        val source = ctx.source
        val ledger = ledger(ctx)
        reply(source, XMoneyMessages.ecoWorking)
        find(ledger, name, playersOnly = false).onSettled { account, _ ->
            if (account == null) {
                reply(source, XMoneyMessages.balanceUnknown, "player" to name)
                return@onSettled
            }
            ledger.history(account.id, 0, 0).onSettled { counted, _ ->
                val total = (counted as? DbOutcome.Ok)?.value?.first
                if (total == null) {
                    reply(source, CoreMessages.commandError)
                    return@onSettled
                }
                if (total == 0L) {
                    reply(source, XMoneyMessages.ecoHistoryEmpty, "player" to account.name)
                    return@onSettled
                }
                val per = XMoneyPlugin.HISTORY_PAGE
                val pages = pages(total, per)
                val number = page.coerceIn(1, pages)
                ledger.history(account.id, (number - 1).toLong() * per, per).onSettled { read, _ ->
                    val rows = (read as? DbOutcome.Ok)?.value?.second
                    if (rows == null) {
                        reply(source, CoreMessages.commandError)
                        return@onSettled
                    }
                    reply(source, XMoneyMessages.ecoHistoryHeader, "player" to account.name, "count" to total)
                    val now = plugin.now()
                    for (row in rows) line(source, ledger, row, now)
                    footer(source, XMoneyMessages.ecoHistoryFooter, number, pages, "/${XMoneyConfig.commands.eco.command} history ${account.name}")
                }
            }
        }
    }

    fun line(source: CommandSource, ledger: Ledger, row: Ledger.HistoryRow, now: Long) {
        val reader = source.player
        val change = texts.render(reader, if (row.amount >= 0) XMoneyMessages.ecoChangeIn else XMoneyMessages.ecoChangeOut, "amount" to money(if (row.amount < 0) -row.amount else row.amount))
        val other = row.other?.let { id -> ledger.accounts[id]?.name ?: id.toString().substring(0, 8) } ?: "?"
        val word = when (row.kind) {
            TransactionKind.START -> XMoneyMessages.wordStart
            TransactionKind.PAY -> if (row.amount < 0) XMoneyMessages.wordPaidTo else XMoneyMessages.wordPaidBy
            TransactionKind.GIVE -> XMoneyMessages.wordGiven
            TransactionKind.TAKE -> XMoneyMessages.wordTaken
            TransactionKind.SET -> XMoneyMessages.wordSet
            TransactionKind.PLUGIN -> if (row.reason.isEmpty()) XMoneyMessages.wordPlugin else XMoneyMessages.wordPluginReason
            TransactionKind.IMPORT -> XMoneyMessages.wordImported
        }
        val what = texts.render(reader, word, "other" to other, "source" to row.source, "reason" to row.reason) ?: Component.EMPTY
        reply(
            source, XMoneyMessages.ecoHistoryLine, "ago" to maxOf(0, now - row.time).milliseconds,
            "change" to Placeholder.component(change ?: Component.EMPTY), "what" to Placeholder.component(what), "balance" to money(row.balance),
        )
    }

    // ---- players ----

    /**
     * The account by [name]: the one known by it, or a player the server knows by it who has played
     * here (whose account is made with the starting balance if they have none). With [playersOnly],
     * not an account a plugin made by name (a town's bank).
     */
    fun find(ledger: Ledger, name: String, playersOnly: Boolean): Pending<Account?> {
        ledger.accounts.named(name)?.let { if (it.player || !playersOnly) return pendingOf(it) }
        if (!NAME.matches(name)) return pendingOf(null)
        val found = newPending<Account?>()
        plugin.server.known(name).onSettled { known, _ ->
            found.resolve(known?.let { ledger.accounts[it.player]?.also { a -> ledger.claim(a, it.name) } ?: ledger.open(it.player, it.name, plugin.compiled.start) })
        }
        return found
    }


    private companion object {
        val NAME = Regex("[A-Za-z0-9_]{1,16}")
    }
}
