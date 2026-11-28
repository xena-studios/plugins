package co.xenastudios.xrewind

import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey

/**
 * Every message xRewind sends, in `lang/<language>.yml` (English here, in code). 1.x's messages
 * keep their English and their paths, less 1.x's `messages.` in front (`messages.lookup.header`
 * is `lookup.header`), so [co.xenastudios.xrewind.config.Legacy] carries an owner's edits across.
 */
public object XRewindMessages : MessageBundle() {
    private val LINE = listOf("ago", "user", "action", "block", "x", "y", "z")

    public val info: MessageKey = message(
        "info", "<prefix>xRewind <highlight><version></highlight> on <platform>: <storage>. <gray>/xrewind lookup | rollback | restore | status | reload | debug",
        "/xrewind on its own, and /xrewind info.", placeholders = listOf("version", "platform", "storage"),
    )

    // ---- lookups and the inspector: 1.x's messages.lookup ----

    public val header: MessageKey = message("lookup.header", "<prefix>Lookup: <highlight><count></highlight> changes", "Above a page of results. <count> is how many there are in all.", placeholders = listOf("count"))
    public val headerAt: MessageKey = message(
        "lookup.header-at", "<prefix>History of <highlight><x> <y> <z></highlight> in <world>",
        "Above the history of one block, from the inspector.", placeholders = listOf("x", "y", "z", "world"),
    )
    public val line: MessageKey = message(
        "lookup.line", "<gray><ago> ago</gray> <highlight><user></highlight> <action> <highlight><block></highlight> <gray>at <x> <y> <z></gray>",
        "One change. <ago> is how long ago, <user> who, <action> what they did, <block> what",
        "to (a block, items, a mob, or what they wrote), <x> <y> <z> <world> where.", placeholders = LINE, optional = listOf("world"),
    )
    public val lineUndone: MessageKey = message(
        "lookup.line-undone", "<strikethrough><gray><ago> ago</gray> <user> <action> <block> <gray>at <x> <y> <z></gray></strikethrough> <gray>(undone)",
        "A change that's been rolled back.", placeholders = LINE, optional = listOf("world"),
    )
    public val footer: MessageKey = message(
        "lookup.footer", "<gray>Page <page> of <pages></gray> <click:run_command:'<next>'><hover:show_text:'<next>'><highlight>Next page »",
        "Below a page. <page>, <pages>, and <next>: the command for the next page.", placeholders = listOf("page", "pages", "next"),
    )
    public val none: MessageKey = message("lookup.none", "<prefix>Nothing found.", "Nothing matched.")
    public val working: MessageKey = message("lookup.working", "<prefix><gray>Looking...", "Sent while waiting on the database.")
    public val invalid: MessageKey = message("lookup.invalid", "<prefix><error><reason>", "The parameters were wrong. <reason> says how (params.*).", placeholders = listOf("reason"))
    public val noPrevious: MessageKey = message("lookup.no-previous", "<prefix><error>Look something up first, then use /xrewind page.", "There's no previous lookup for /xrewind page.")
    public val notReady: MessageKey = message("lookup.not-ready", "<prefix><error>The database isn't ready yet. /xrewind status says why.", "The database isn't ready yet.")
    public val inspectorOn: MessageKey = message("lookup.inspector-on", "<prefix>Inspector <success>on</success>. Click a block to see its history; place one to see what was there.")
    public val inspectorOff: MessageKey = message("lookup.inspector-off", "<prefix>Inspector <highlight>off</highlight>.")

    // The words for each kind of change, as <action> shows them.
    public val placed: MessageKey = message("lookup.words.placed", "placed", "The words for each kind of change, as <action> shows them.")
    public val broke: MessageKey = message("lookup.words.broke", "broke")
    public val clicked: MessageKey = message("lookup.words.clicked", "used")
    public val added: MessageKey = message("lookup.words.added", "added")
    public val took: MessageKey = message("lookup.words.took", "took")
    public val killed: MessageKey = message("lookup.words.killed", "killed")
    public val wrote: MessageKey = message("lookup.words.wrote", "wrote")
    public val said: MessageKey = message("lookup.words.said", "said")
    public val ran: MessageKey = message("lookup.words.ran", "ran")
    public val joined: MessageKey = message("lookup.words.joined", "joined")
    public val quit: MessageKey = message("lookup.words.quit", "quit")

    // ---- what's wrong with the parameters: <reason> in lookup.invalid ----

    public val notAFlag: MessageKey = message("params.not-a-flag", "<arg> isn't a flag. Use #preview, #count or #silent.", "1.x had these in English only.", placeholders = listOf("arg"))
    public val notAParameter: MessageKey = message("params.not-a-parameter", "<arg> isn't a parameter. Use u:, t:, r:, a:, i:, e: or w:, such as t:1h.", placeholders = listOf("arg"))
    public val needsValue: MessageKey = message("params.needs-value", "<arg> needs something after the colon.", placeholders = listOf("arg"))
    public val unknownKey: MessageKey = message("params.unknown-key", "<key>: isn't a parameter. Use u:, t:, r:, a:, i:, e: or w:.", placeholders = listOf("key"))
    public val badRadius: MessageKey = message("params.bad-radius", "r:<value> isn't a radius. Use a number of blocks, #world or #global.", placeholders = listOf("value"))
    public val badAction: MessageKey = message(
        "params.bad-action", "a:<value> isn't an action. Use block, +block, -block, container, +container, -container, kill, click, sign, chat, command or session.",
        placeholders = listOf("value"),
    )
    public val badBlock: MessageKey = message("params.bad-block", "<value> isn't a block or an item.", placeholders = listOf("value"))
    public val badTime: MessageKey = message("params.bad-time", "t:<value> isn't a time. Use a number and a unit, such as 30m, 6h, 2d or 1w.", placeholders = listOf("value"))
    public val consoleLookup: MessageKey = message("params.console-lookup", "From the console, add w:\\<world> or r:#global.")
    public val consoleRollback: MessageKey = message("params.console-rollback", "From the console, use r:#world with w:\\<world>, or r:#global.")

    // ---- rollbacks: 1.x's messages.rollback ----

    public val needTime: MessageKey = message("rollback.need-time", "<prefix><error>Add a time, such as <highlight>t:1h</highlight>, so it can't reach further back than you meant.", "A rollback or restore without t:.")
    public val tooFar: MessageKey = message(
        "rollback.too-far", "<prefix><error>That's past the <highlight><max></highlight>-block limit. r:#world, r:#global and bigger radii need <highlight>xrewind.rollback.global</highlight>.",
        "Past max-radius without permission. <max> is the limit.", placeholders = listOf("max"),
    )
    public val busy: MessageKey = message("rollback.busy", "<prefix><error>Another rollback is running. Wait for it to finish.", "Another one is already running.")
    public val nothing: MessageKey = message("rollback.nothing", "<prefix>Nothing to change.")
    public val preview: MessageKey = message(
        "rollback.preview",
        "<prefix>Previewing <highlight><count></highlight> blocks, for you only. <click:run_command:'/xrewind apply'><hover:show_text:'/xrewind apply'><success>[Apply]</success></hover></click> <click:run_command:'/xrewind cancel'><hover:show_text:'/xrewind cancel'><error>[Cancel]</error></hover></click>",
        "A #preview is showing. <count> is how many blocks.", placeholders = listOf("count"),
    )
    public val noPreview: MessageKey = message("rollback.no-preview", "<prefix><error>You have no preview. Add #preview to a rollback or restore.")
    public val cancelled: MessageKey = message("rollback.cancelled", "<prefix>Preview cancelled. Nothing changed.")
    public val progress: MessageKey = message("rollback.progress", "<highlight><verb></highlight>: <done> of <total> blocks", "On the action bar while it works.", placeholders = listOf("verb", "done", "total"))
    public val done: MessageKey = message("rollback.done", "<prefix><success><verb></success> <highlight><count></highlight> blocks in <seconds>s.", "When it's finished.", placeholders = listOf("verb", "count", "seconds"))
    public val skipped: MessageKey = message(
        "rollback.skipped", "<prefix><error><count> blocks were stored by a Minecraft version this one can't read, and were left as they are.",
        "Stored blocks this server couldn't read.", placeholders = listOf("count"),
    )
    public val noUndo: MessageKey = message("rollback.no-undo", "<prefix><error>Nothing to undo. Undo works on your last rollback or restore since the server started.")
    public val stopped: MessageKey = message("rollback.stopped", "<prefix><error>Another plugin stopped it before it started. Nothing changed.", "Another plugin stopped a rollback, restore or undo before it started.")
    public val verbRollback: MessageKey = message("rollback.verbs.rollback", "Rolled back", "<verb> in rollback.progress and rollback.done.")
    public val verbRestore: MessageKey = message("rollback.verbs.restore", "Restored")
    public val verbUndo: MessageKey = message("rollback.verbs.undo", "Undid")

    // ---- purges and imports: 1.x's messages.upkeep ----

    public val purgeTooRecent: MessageKey = message("upkeep.purge-too-recent", "<prefix><error>Purge history at least a day old: <highlight>t:1d</highlight> or more.", "A purge of less than a day.")
    public val purging: MessageKey = message("upkeep.purging", "<prefix><gray>Purging...")
    public val purged: MessageKey = message("upkeep.purged", "<prefix><success>Purged</success> <highlight><count></highlight> changes older than <time>.", "<count> changes deleted; <time> how old.", placeholders = listOf("count", "time"))
    public val importing: MessageKey = message(
        "upkeep.importing", "<prefix>Importing CoreProtect's history from <highlight><source></highlight>. Logging carries on meanwhile; the console shows progress.",
        "<source> is where CoreProtect's history is read from.", placeholders = listOf("source"),
    )
    public val imported: MessageKey = message("upkeep.imported", "<prefix><success>Imported</success> <highlight><count></highlight> changes from CoreProtect in <seconds>s.", placeholders = listOf("count", "seconds"))
    public val importedBefore: MessageKey = message(
        "upkeep.imported-before", "<prefix><error>CoreProtect's history was already imported (<date>). <highlight>#again</highlight> imports it again, and what came across before shows twice.",
        "It was imported before.", placeholders = listOf("date"),
    )
    public val importFailed: MessageKey = message("upkeep.import-failed", "<prefix><error>Couldn't read CoreProtect's history: <reason>", "CoreProtect's database couldn't be read.", placeholders = listOf("reason"))
    public val importRunning: MessageKey = message("upkeep.import-running", "<prefix><error>An import is already running.")
    public val purgeInterrupted: MessageKey = message(
        "upkeep.purge-interrupted", "<prefix><error>The purge was stopped partway, after deleting <highlight><count></highlight> changes. Run it again to finish.",
        "The server stopped a purge partway.", placeholders = listOf("count"),
    )
    public val importInterrupted: MessageKey = message("upkeep.import-interrupted", "<prefix><error>The import was stopped partway. Run it again with <highlight>#again</highlight>; what came across before shows twice.")
    public val importNotHere: MessageKey = message("upkeep.import-not-here", "<prefix><error>CoreProtect is a Bukkit plugin, so there's nothing of it to import on this server.", "/xrewind import on Fabric.")

    // ---- /xrewind status ----

    public val status: MessageKey = message(
        "status",
        "<prefix><highlight>Storage</highlight>: <storage>\n<highlight>Saved</highlight> since startup: <written> changes. Waiting: <waiting>. Missed: <missed>.\n<highlight>Last batch</highlight>: <batch>\n<highlight>Problem</highlight>: <problem>",
        "Whether logging is keeping up. <batch> is status.batch or status.no-batch, <problem> what's stopping writes or status.no-problem.",
        placeholders = listOf("storage", "written", "waiting", "missed", "batch", "problem"),
    )
    public val statusBatch: MessageKey = message("status.batch", "<count> changes in <ms> ms", placeholders = listOf("count", "ms"))
    public val statusNoBatch: MessageKey = message("status.no-batch", "none yet")
    public val statusNoProblem: MessageKey = message("status.no-problem", "none")
    public val statusNotReady: MessageKey = message("status.not-ready", "not ready")

    /** Every message the lang files hold: the core's, then xRewind's own. */
    public val catalog: MessageCatalog = MessageCatalog.of(CoreMessages, this)
}
