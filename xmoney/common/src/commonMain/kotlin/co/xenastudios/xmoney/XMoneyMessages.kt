package co.xenastudios.xmoney

import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey

/**
 * Every message xMoney sends, in `lang/<language>.yml` (English here, in code). The keys are the
 * 1.x config's message settings without `messages.`: `pay.sent` was `messages.pay.sent`, so an
 * owner's 1.x wording carries straight over ([co.xenastudios.xmoney.config.Legacy]). 1.x's
 * `messages.error.*` are the core's `core.command.*`.
 */
public object XMoneyMessages : MessageBundle() {
    private const val PAGE_LINKS =
        "<has_previous> <click:run_command:'<previous>'><hover:show_text:'<previous>'><highlight>« Previous</highlight></hover></click></has_previous>" +
            "<has_next> <click:run_command:'<next>'><hover:show_text:'<next>'><highlight>Next »</highlight></hover></click></has_next>"

    public val info: MessageKey = message(
        "info", "<prefix>xMoney <highlight><version></highlight> on <platform>, keeping <accounts> accounts in <storage>. <gray>/xmoney status | reload | debug",
        "/xmoney on its own.", placeholders = listOf("version", "platform", "accounts", "storage"),
    )
    public val status: MessageKey = message(
        "status.report",
        "<prefix><highlight>Storage</highlight>: <storage><network>\n<highlight>Accounts</highlight>: <accounts>. Players hold <total>\n" +
            "<highlight>Waiting to be saved</highlight>: <waiting>\n<highlight>Problem</highlight>: <problem>",
        "/xmoney status: where balances are kept, and whether saving is keeping up. <network> is \", shared with other servers\" on a network.",
        placeholders = listOf("storage", "network", "accounts", "total", "waiting", "problem"),
    )
    public val statusShared: MessageKey = message("status.shared", ", shared with other servers (experimental)", "What <network> says in /xmoney status on a network.")
    public val statusNone: MessageKey = message("status.none", "none", "What <problem> says in /xmoney status when all is well.")
    public val opening: MessageKey = message(
        "opening", "<prefix><gray>The bank is still opening its database. Try again in a moment.",
        "A command run in the seconds while the server starts, before the balances are read.",
    )
    public val noDatabase: MessageKey = message(
        "no-database", "<prefix><error>xMoney couldn't open its database, so there's no economy. The console says why.",
        "Any money command while the database couldn't be opened (fix storage in config.yml and restart).",
    )

    public val balanceOwn: MessageKey = message("balance.own", "<prefix>Balance: <highlight><balance>", "Your own. <balance> is formatted, as is every amount in messages.", placeholders = listOf("balance"))
    public val balanceOther: MessageKey = message(
        "balance.other", "<prefix><highlight><player></highlight>'s balance: <highlight><balance>", "Someone else's. <player> is their name.",
        placeholders = listOf("player", "balance"),
    )
    public val balanceUnknown: MessageKey = message(
        "balance.unknown", "<prefix><error><highlight><player></highlight> has never played here.", "Nobody by that name has played here. <player> is the name typed.",
        placeholders = listOf("player"),
    )

    public val paySent: MessageKey = message(
        "pay.sent", "<prefix><success>Sent</success> <highlight><amount></highlight> to <highlight><player></highlight>. You have <balance> left.",
        "To the payer. <amount>, <player>, and <balance>: what they have left.", placeholders = listOf("amount", "player", "balance"),
    )
    public val payReceived: MessageKey = message(
        "pay.received", "<prefix><success>Received</success> <highlight><amount></highlight> from <highlight><player></highlight>.",
        "To whoever is paid, if they're online. <amount>, <player>: who paid.", placeholders = listOf("amount", "player"),
    )
    public val paySelf: MessageKey = message("pay.self", "<prefix><error>You can't pay yourself.", "/pay to yourself.")
    public val payTooLittle: MessageKey = message(
        "pay.too-little", "<prefix><error>The least you can send is <highlight><minimum></highlight>.", "Below the minimum. <minimum>.",
        placeholders = listOf("minimum"),
    )
    public val payNotEnough: MessageKey = message(
        "pay.not-enough", "<prefix><error>You don't have <highlight><amount></highlight>. You have <balance>.", "The payer hasn't got it. <amount>, <balance>.",
        placeholders = listOf("amount", "balance"),
    )
    public val payConfirm: MessageKey = message(
        "pay.confirm",
        "<prefix>Send <highlight><amount></highlight> to <highlight><player></highlight>? <click:run_command:'<command>'><hover:show_text:'<command>'><success>[Confirm]</success></hover></click>",
        "A payment of confirm-above or more asks first. <command> runs it, confirmed; put it in a click.",
        placeholders = listOf("amount", "player", "command"),
    )
    public val payLimit: MessageKey = message("pay.limit", "<prefix><error><highlight><player></highlight> can't hold that much.", "It would take them over max-balance. <player>.", placeholders = listOf("player"))
    public val payCancelled: MessageKey = message("pay.cancelled", "<prefix><error>That payment was refused.", "Another plugin refused it.")
    public val payUnavailable: MessageKey = message(
        "pay.unavailable", "<prefix><error>The bank can't be reached right now. Nothing was sent; try again in a moment.",
        "On a network, the shared database didn't answer in time. Nothing was sent.",
    )

    public val baltopHeader: MessageKey = message(
        "baltop.header", "<prefix>Richest players. <gray>All <count>: <total>",
        "Above the list. <total> is every player's money added up, <count> how many players.", placeholders = listOf("count", "total"),
    )
    public val baltopLine: MessageKey = message(
        "baltop.line", "<gray>#<rank></gray> <highlight><player></highlight> <balance>", "One player. <rank>, <player>, <balance>.",
        placeholders = listOf("rank", "player", "balance"),
    )
    public val baltopEmpty: MessageKey = message("baltop.empty", "<prefix>Nobody has any money yet.", "/baltop with no players yet.")
    public val baltopFooter: MessageKey = message(
        "baltop.footer", "<gray>Page <page> of <pages></gray>$PAGE_LINKS",
        "Below a page, when there's more than one. <page>, <pages>, and <next>/<previous>: the commands.",
        placeholders = listOf("page", "pages", "next", "previous", "has_next", "has_previous"),
    )

    public val ecoGiven: MessageKey = message(
        "eco.given", "<prefix><success>Gave</success> <highlight><player></highlight> <amount>. They have <balance>.",
        "<amount>, <player>, and <balance>: what they have now.", placeholders = listOf("player", "amount", "balance"),
    )
    public val ecoTaken: MessageKey = message(
        "eco.taken", "<prefix><success>Took</success> <amount> from <highlight><player></highlight>. They have <balance>.",
        "/eco take.", placeholders = listOf("player", "amount", "balance"),
    )
    public val ecoSet: MessageKey = message("eco.set", "<prefix><success>Set</success> <highlight><player></highlight>'s balance to <balance>.", "/eco set.", placeholders = listOf("player", "balance"))
    public val ecoReset: MessageKey = message("eco.reset", "<prefix><success>Reset</success> <highlight><player></highlight>'s balance to <balance>.", "/eco reset.", placeholders = listOf("player", "balance"))
    public val ecoNotEnough: MessageKey = message(
        "eco.not-enough", "<prefix><error><highlight><player></highlight> only has <balance>.", "/eco take for more than they have. <player>, <balance>.",
        placeholders = listOf("player", "balance"),
    )
    public val ecoLimit: MessageKey = message("eco.limit", "<prefix><error><highlight><player></highlight> can't hold that much.", "It would take them over max-balance. <player>.", placeholders = listOf("player"))
    public val ecoCancelled: MessageKey = message("eco.cancelled", "<prefix><error>Another plugin refused that change.", "Another plugin refused it.")
    public val ecoUnavailable: MessageKey = message(
        "eco.unavailable", "<prefix><error>The shared database can't be reached right now. Nothing changed.",
        "On a network, the shared database didn't answer in time. Nothing changed.",
    )
    public val ecoWorking: MessageKey = message("eco.working", "<prefix><gray>Looking...", "Sent while the history is read from the database.")
    public val ecoHistoryHeader: MessageKey = message(
        "eco.history-header", "<prefix>History of <highlight><player></highlight>: <count> changes", "Above a page of history. <player>, <count>: how many changes in all.",
        placeholders = listOf("player", "count"),
    )
    public val ecoHistoryLine: MessageKey = message(
        "eco.history-line", "<gray><ago> ago</gray> <change> <what> <gray>→ <balance>",
        "One change. <ago>: how long ago, <change>: + or - the amount, <what>: what it was, <balance>: the balance after.",
        placeholders = listOf("ago", "change", "what", "balance"),
    )
    public val ecoHistoryEmpty: MessageKey = message("eco.history-empty", "<prefix><highlight><player></highlight> has no history.", "The player has no history.", placeholders = listOf("player"))
    public val ecoHistoryFooter: MessageKey = message(
        "eco.history-footer", "<gray>Page <page> of <pages></gray>$PAGE_LINKS", "Below a page of history, when there's more than one.",
        placeholders = listOf("page", "pages", "next", "previous", "has_next", "has_previous"),
    )
    public val ecoChangeIn: MessageKey = message("eco.change-in", "<success>+<amount></success>", "How <change> looks when money came in. <amount>.", placeholders = listOf("amount"))
    public val ecoChangeOut: MessageKey = message("eco.change-out", "<error>-<amount></error>", "How <change> looks when money went out. <amount>.", placeholders = listOf("amount"))

    public val wordStart: MessageKey = message("eco.words.start", "starting balance", "What <what> shows for a starting balance.")
    public val wordPaidTo: MessageKey = message("eco.words.paid-to", "paid <other>", "A payment out. <other>: the other player.", placeholders = listOf("other"))
    public val wordPaidBy: MessageKey = message("eco.words.paid-by", "from <other>", "A payment in. <other>: the other player.", placeholders = listOf("other"))
    public val wordGiven: MessageKey = message("eco.words.given", "given by <source>", "/eco give. <source>: who did it.", placeholders = listOf("source"))
    public val wordTaken: MessageKey = message("eco.words.taken", "taken by <source>", "/eco take.", placeholders = listOf("source"))
    public val wordSet: MessageKey = message("eco.words.set", "set by <source>", "/eco set and reset.", placeholders = listOf("source"))
    public val wordPlugin: MessageKey = message("eco.words.plugin", "<source>", "A plugin's change, without a reason.", placeholders = listOf("source"))
    public val wordPluginReason: MessageKey = message("eco.words.plugin-reason", "<source>: <reason>", "A plugin's change, with a reason.", placeholders = listOf("source", "reason"))
    public val wordImported: MessageKey = message("eco.words.imported", "imported from <source>", "An import.", placeholders = listOf("source"))

    public val importDryRun: MessageKey = message(
        "imports.dry-run",
        "<prefix><highlight><source></highlight> has <highlight><count></highlight> balances, <total> in all (<skipped> can't be imported). " +
            "Importing sets each of those players' balance here to theirs. <click:run_command:'<command>'><hover:show_text:'<command>'><success>[Import]</success></hover></click>",
        "A dry run: what an import would do, changing nothing. <source>, <count> accounts, <total>: their money added up, <skipped> that can't be imported, <command>: the import, confirmed.",
        placeholders = listOf("source", "count", "total", "skipped", "command"),
    )
    public val importDone: MessageKey = message(
        "imports.done", "<prefix><success>Imported</success> <highlight><count></highlight> balances from <source>. <skipped> skipped.",
        "<count> balances set from <source>, <skipped> skipped.", placeholders = listOf("count", "source", "skipped"),
    )
    public val importNothing: MessageKey = message("imports.nothing", "<prefix><error>Nothing to import from <source>: <reason>", "There's nothing to import. <source>, <reason>.", placeholders = listOf("source", "reason"))
    public val importFailed: MessageKey = message("imports.failed", "<prefix><error>Couldn't read <source>: <reason>", "The other plugin's data couldn't be read. <source>, <reason>.", placeholders = listOf("source", "reason"))
    public val importRunning: MessageKey = message("imports.running", "<prefix><error>An import is already running.", "/xmoney import while one runs.")
    public val importReading: MessageKey = message("imports.reading", "<prefix><gray>Reading <source>...", "Sent as the other plugin's data is read. <source>.", placeholders = listOf("source"))
    public val importOverwrite: MessageKey = message(
        "imports.overwrite",
        "<prefix><error><highlight><count></highlight> of these accounts have changed here since, and importing sets them back to <source>'s balances. " +
            "<click:run_command:'<command>'><hover:show_text:'<command>'><error>[Import anyway]</error></hover></click>",
        "Some of the accounts have changed in xMoney since (payments, /eco, other plugins), and importing would set them back. <count> of them, <source>, <command>: the import anyway.",
        placeholders = listOf("count", "source", "command"),
    )
    public val importUnknown: MessageKey = message(
        "imports.unknown", "<prefix><error>xMoney can import from <sources>.", "/xmoney import with a source it doesn't know. <sources>: the ones it does.",
        placeholders = listOf("sources"),
    )

    public val amountInvalid: MessageKey = message(
        "amount.invalid", "<prefix><error><highlight><amount></highlight> isn't an amount. Try 250, 12.5 or 1.5k.", "Not a number. <amount> is what was typed.",
        placeholders = listOf("amount"),
    )
    public val amountTooPrecise: MessageKey = message(
        "amount.too-precise", "<prefix><error>Amounts have at most <highlight><decimals></highlight> decimals.", "More decimals than the currency has. <decimals>.",
        placeholders = listOf("decimals"),
    )
    public val amountTooLarge: MessageKey = message("amount.too-large", "<prefix><error>That's more than an account can hold.", "Past the most an account can hold.")

    /** Every message the lang files hold: the core's, then xMoney's own. */
    public val catalog: MessageCatalog = MessageCatalog.of(CoreMessages, this)
}
