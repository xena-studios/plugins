package co.xenastudios.xmoney

import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome as DbOutcome
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.locked
import co.xenastudios.xmoney.api.Amount
import co.xenastudios.xmoney.api.Rounding
import co.xenastudios.xmoney.api.TransactionKind
import co.xenastudios.xmoney.ledger.Account
import co.xenastudios.xmoney.ledger.Cause

/**
 * `/xmoney import <source> [confirm [overwrite]]`, as 1.x had it: a dry run until confirmed,
 * which says how many balances and how much; balances set through the ledger as `IMPORT`, so
 * each one's balance before is in the history. If any of those accounts changed in xMoney since
 * (any ledger row but a start or an import), it says how many, and needs `overwrite` too.
 * Negative balances come in as 0; one past `max-balance`, or a uuid twice, is skipped.
 */
internal object Imports {
    private val lock = Any()
    private var running = false

    /** One balance to set, in units. */
    class Line(val found: BalanceSource.Found, val units: Long)

    /** What an import would do. */
    class Plan(val source: String, val lines: List<Line>, val skipped: Int, val total: Long)

    fun plan(source: String, found: List<BalanceSource.Found>, max: Long): Plan {
        val lines = ArrayList<Line>()
        val seen = HashSet<String>()
        var skipped = 0
        var total = 0L
        for (one in found) {
            // Kept to four places, rounded down, like any amount paid in.
            val amount = Amount.parse(one.balance.trim(), Rounding.DOWN)
            val units = when {
                amount == null -> -1
                amount.isNegative -> 0
                else -> amount.units
            }
            if (units < 0 || units > max || !seen.add(one.id.toString())) {
                skipped++
                continue
            }
            lines += Line(one, units)
            total += units
        }
        return Plan(source, lines, skipped, total)
    }

    fun run(plugin: XMoneyPlugin, sender: CommandSource, which: String, confirm: Boolean, overwrite: Boolean) {
        val texts = plugin.texts
        val ledger = plugin.ledger ?: return texts.reply(sender, if (plugin.opening) XMoneyMessages.opening else XMoneyMessages.noDatabase)
        val source = plugin.sources?.make(which) ?: return texts.reply(sender, XMoneyMessages.importUnknown, "sources" to plugin.sources?.ids.orEmpty().joinToString(", "))
        val claimed = locked(lock) {
            if (running) false else {
                running = true
                true
            }
        }
        if (!claimed) return texts.reply(sender, XMoneyMessages.importRunning)
        fun done() = locked(lock) { running = false }
        texts.reply(sender, XMoneyMessages.importReading, "source" to source.name)
        source.read().onSettled { read, error ->
            if (read == null || read.failed != null) {
                done()
                if (error != null) plugin.log.error("Importing from ${source.name} failed", error)
                return@onSettled if (read?.failed != null) texts.reply(sender, XMoneyMessages.importFailed, "source" to source.name, "reason" to read.failed)
                else texts.reply(sender, CoreMessages.commandError)
            }
            if (read.missing != null) {
                done()
                return@onSettled texts.reply(sender, XMoneyMessages.importNothing, "source" to source.name, "reason" to read.missing)
            }
            val plan = plan(source.name, read.found, plugin.compiled.max)
            if (plan.lines.isEmpty()) {
                done()
                return@onSettled texts.reply(
                    sender, XMoneyMessages.importNothing, "source" to source.name,
                    "reason" to "it has no balances that can be imported (${plan.skipped} skipped).",
                )
            }
            // Read once every change so far is saved: one made just before would otherwise be missed, and set back without asking.
            ledger.changedHere().onSettled { changed, _ ->
                val here = (changed as? DbOutcome.Ok)?.value
                if (here == null) {
                    done()
                    return@onSettled texts.reply(sender, CoreMessages.commandError)
                }
                val overwrites = plan.lines.count { it.found.id in here }
                val command = "/${XMoneyPlugin.PLUGIN_ID} import $which confirm"
                if (!confirm || (overwrites > 0 && !overwrite)) {
                    done()
                    if (!confirm) {
                        texts.reply(
                            sender, XMoneyMessages.importDryRun, "source" to plan.source, "count" to plan.lines.size,
                            "total" to plugin.format(plan.total), "skipped" to plan.skipped, "command" to command,
                        )
                    }
                    if (overwrites > 0) {
                        texts.reply(sender, XMoneyMessages.importOverwrite, "source" to plan.source, "count" to overwrites, "command" to "$command overwrite")
                    }
                    return@onSettled
                }
                val targets = ArrayList<Pair<Account, Long>>(plan.lines.size)
                for (line in plan.lines) {
                    val account = ledger.accounts[line.found.id] ?: ledger.account(line.found.id, line.found.name, line.found.player)
                    targets += account to line.units
                }
                ledger.setAll(targets, Cause(TransactionKind.IMPORT, plan.source, "")).onSettled { set, _ ->
                    done()
                    val count = set ?: 0
                    val failed = plan.lines.size - count
                    if (failed > 0) plugin.log.warn("$failed balances from ${plan.source} couldn't be set; /eco history shows what each account has.")
                    val skipped = plan.skipped + failed
                    plugin.log.info("Imported $count balances from ${plan.source}, $skipped skipped.")
                    texts.reply(sender, XMoneyMessages.importDone, "source" to plan.source, "count" to count, "skipped" to skipped)
                }
            }
        }
    }
}
