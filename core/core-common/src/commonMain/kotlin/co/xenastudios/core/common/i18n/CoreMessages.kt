package co.xenastudios.core.common.i18n

import co.xenastudios.core.common.text.Placeholder

/**
 * The core's own messages, in every plugin's `lang/` files beside the plugin's own (put it
 * first in the plugin's [MessageCatalog]): the chat pages' footer and the admin subcommands'
 * replies. More come as the core's features do: reload, errors, the update notice.
 */
public object CoreMessages : MessageBundle() {

    /** The footer under a long list's page: see [ChatPages]. */
    public val pagesFooter: MessageKey = message(
        "core.pages.footer",
        "<gray>Page <page> of <pages></gray>" +
            "<has_previous> <click:run_command:'<previous>'><hover:show_text:'<previous>'><highlight>« Previous</highlight></hover></click></has_previous>" +
            "<has_next> <click:run_command:'<next>'><hover:show_text:'<next>'><highlight>Next »</highlight></hover></click></has_next>",
        "Under a long list in chat (/homes, /history, ...) when it has more than one page.",
        "<previous> and <next> are the list's command for those pages; what's inside",
        "<has_previous>...</has_previous> or <has_next>...</has_next> shows only when there's such a page.",
        placeholders = listOf("page", "pages", "previous", "next", "has_previous", "has_next"),
    )

    // /<plugin> debug: one line per health check, then where the report went.

    /** Before the health checks' lines. */
    public val debugChecking: MessageKey = message(
        "core.debug.checking", "<prefix>Checking <highlight><plugin></highlight>...",
        "/<plugin> debug, before the health checks' results.",
        placeholders = listOf("plugin"),
    )

    /** The database answered. */
    public val debugDatabaseOk: MessageKey = message(
        "core.debug.database.ok", "<success>✔</success> Database: <highlight><database></highlight> answered in <millis> ms.",
        "/<plugin> debug: the database answered. <database> is where it is, such as \"MySQL at db:3306/minecraft\".",
        placeholders = listOf("database", "millis"),
    )

    /** The database didn't answer. */
    public val debugDatabaseFailed: MessageKey = message(
        "core.debug.database.failed", "<error>✘</error> Database: <highlight><database></highlight> can't be reached: <reason>",
        "/<plugin> debug: the database didn't answer. <reason> is the driver's, in English.",
        placeholders = listOf("database", "reason"),
    )

    /** Every dependency is there. */
    public val debugDependenciesOk: MessageKey = message(
        "core.debug.dependencies.ok", "<success>✔</success> Dependencies: <names>.",
        "/<plugin> debug: every plugin or mod it depends on is there. <names> lists them, or says none.",
        placeholders = listOf("names"),
    )

    /** Some optional dependencies are missing: what needs them is off. */
    public val debugDependenciesOptional: MessageKey = message(
        "core.debug.dependencies.optional", "<gold>!</gold> Dependencies: <names> not installed, so what needs them is off.",
        "/<plugin> debug: optional plugins or mods that aren't installed.",
        placeholders = listOf("names"),
    )

    /** A dependency it needs is missing. */
    public val debugDependenciesMissing: MessageKey = message(
        "core.debug.dependencies.missing", "<error>✘</error> Dependencies: <highlight><names></highlight> missing.",
        "/<plugin> debug: plugins or mods it needs that aren't installed.",
        placeholders = listOf("names"),
    )

    /** Tasks ran on the threads they should. */
    public val debugThreadingOk: MessageKey = message(
        "core.debug.threading.ok", "<success>✔</success> Threads: tasks run where they should (<threading>).",
        "/<plugin> debug: a task on the global thread and one off it each ran where it should.",
        "<threading> is how the server runs work, in English: \"Folia's regions\", \"one main thread\".",
        placeholders = listOf("threading"),
    )

    /** A task didn't run where it should, or at all. */
    public val debugThreadingFailed: MessageKey = message(
        "core.debug.threading.failed", "<error>✘</error> Threads: <reason>",
        "/<plugin> debug: a task ran on the wrong thread, or didn't run. <reason> is in English.",
        placeholders = listOf("reason"),
    )

    /** The config and language files read cleanly. */
    public val debugConfigOk: MessageKey = message(
        "core.debug.config.ok", "<success>✔</success> Config: read without problems.",
        "/<plugin> debug: the config and language files had nothing wrong when they were last read.",
    )

    /** The config or language files had problems. */
    public val debugConfigProblems: MessageKey = message(
        "core.debug.config.problems", "<gold>!</gold> Config: problems found (<count>), the first: <first>",
        "/<plugin> debug: problems found the last time the files were read; the console and the report have them all.",
        placeholders = listOf("count", "first"),
    )

    /** Every module wanted is on. */
    public val debugModulesOk: MessageKey = message(
        "core.debug.modules.ok", "<success>✔</success> Modules: <count> on.",
        "/<plugin> debug: every module the config turns on is running.",
        placeholders = listOf("count"),
    )

    /** Modules that failed and turned themselves off. */
    public val debugModulesFailed: MessageKey = message(
        "core.debug.modules.failed", "<error>✘</error> Modules: <highlight><modules></highlight> turned off after failing.",
        "/<plugin> debug: modules that failed. The report says why.",
        placeholders = listOf("modules"),
    )

    /** A check that didn't answer in time, or threw. */
    public val debugCheckBroken: MessageKey = message(
        "core.debug.check-broken", "<error>✘</error> <check>: <reason>",
        "/<plugin> debug: a check that didn't finish. <check> is its name and <reason> why, in English.",
        placeholders = listOf("check", "reason"),
    )

    /** The report was uploaded. */
    public val debugUploaded: MessageKey = message(
        "core.debug.uploaded",
        "<prefix>Debug report: <click:open_url:'<url>'><hover:show_text:'Open it'><highlight><url></highlight></hover></click>. " +
            "Share this link when asking for help; passwords and tokens are removed.",
        "/<plugin> debug: the report's link at a paste service (mclo.gs).",
        placeholders = listOf("url"),
    )

    /** The report couldn't be uploaded, so it's a file instead. */
    public val debugSaved: MessageKey = message(
        "core.debug.saved", "<prefix>Couldn't upload the debug report (<reason>), so it's in <highlight><file></highlight> in the plugin's folder.",
        "/<plugin> debug: the upload failed, so the report was written to a file. <reason> is in English.",
        placeholders = listOf("reason", "file"),
    )

    /** Uploads are off, so the report is a file. */
    public val debugSavedLocally: MessageKey = message(
        "core.debug.saved-locally", "<prefix>Debug report saved as <highlight><file></highlight> in the plugin's folder.",
        "/<plugin> debug, when uploading is turned off.",
        placeholders = listOf("file"),
    )

    /** Neither upload nor file worked. */
    public val debugFailed: MessageKey = message(
        "core.debug.failed", "<prefix><error>Couldn't save the debug report: <reason></error>",
        "/<plugin> debug: the report couldn't be uploaded or written. <reason> is in English.",
        placeholders = listOf("reason"),
    )

    // /<plugin> import <source> [--dry-run]

    /** The other plugin's data isn't there. */
    public val importNotFound: MessageKey = message(
        "core.import.not-found", "<prefix><error>There's no <source> data here to import.</error>",
        "/<plugin> import: the other plugin's files or tables weren't found.",
        placeholders = listOf("source"),
    )

    /** Another import or migration is running. */
    public val importBusy: MessageKey = message(
        "core.import.busy", "<prefix><error>An import is already running; wait for it to finish.</error>",
        "/<plugin> import while one is running.",
    )

    /** A dry run's counts. */
    public val importDryRun: MessageKey = message(
        "core.import.dry-run",
        "<prefix>Dry run from <source>: <highlight><importable></highlight> of <total> records would be imported, <skipped> skipped. Nothing was changed.",
        "/<plugin> import <source> --dry-run: what a real run would do.",
        placeholders = listOf("source", "importable", "total", "skipped"),
    )

    /** An import starts. */
    public val importStarted: MessageKey = message(
        "core.import.started", "<prefix>Importing from <source> (records: <highlight><total></highlight>)...",
        "/<plugin> import: the run starts.",
        placeholders = listOf("total", "source"),
    )

    /** An import carries on where a stopped one left off. */
    public val importResumed: MessageKey = message(
        "core.import.resumed", "<prefix>Carrying on the import from <source> where it stopped, after <done> of <total> records...",
        "/<plugin> import: a run that stopped before is carried on.",
        placeholders = listOf("source", "done", "total"),
    )

    /** How far an import is, now and then. */
    public val importProgress: MessageKey = message(
        "core.import.progress", "<prefix><done> of <total> records from <source>...",
        "/<plugin> import: progress, every few seconds.",
        placeholders = listOf("done", "total", "source"),
    )

    /** One record that couldn't be imported; the rest carry on. */
    public val importRecordFailed: MessageKey = message(
        "core.import.record-failed", "<prefix><error>Skipped <record>: <reason></error>",
        "/<plugin> import: one record that failed. The import carries on; the console lists them all.",
        placeholders = listOf("record", "reason"),
    )

    /** An import finished. */
    public val importDone: MessageKey = message(
        "core.import.done", "<prefix><success>Imported from <source>: <imported>.</success> Skipped: <skipped>, failed: <failed>.",
        "/<plugin> import: the run finished.",
        placeholders = listOf("imported", "source", "skipped", "failed"),
    )

    /** An import stopped partway; running it again carries on. */
    public val importStopped: MessageKey = message(
        "core.import.stopped", "<prefix><error>The import from <source> stopped: <reason></error> Run it again to carry on where it left off.",
        "/<plugin> import: the run stopped, such as when the other plugin's database went away. <reason> is in English.",
        placeholders = listOf("source", "reason"),
    )

    // /<plugin> migrate <sqlite|mysql> [--force]

    /** The data is already there. */
    public val migrateSame: MessageKey = message(
        "core.migrate.same", "<prefix><error>The data is already in <type>.</error>",
        "/<plugin> migrate to the storage type in use.",
        placeholders = listOf("type"),
    )

    /** A migration or import is running. */
    public val migrateBusy: MessageKey = message(
        "core.migrate.busy", "<prefix><error>A migration is already running; wait for it to finish.</error>",
        "/<plugin> migrate while one is running.",
    )

    /** The target has data, and --force wasn't given. */
    public val migrateNotEmpty: MessageKey = message(
        "core.migrate.not-empty",
        "<prefix><error>The <type> database already has data (<tables>).</error> Add --force to replace it with this server's.",
        "/<plugin> migrate: the target isn't empty. <tables> lists its tables with their rows.",
        placeholders = listOf("type", "tables"),
    )

    /** The copy starts. */
    public val migrateStarted: MessageKey = message(
        "core.migrate.started", "<prefix>Copying the data from <from> to <type>...",
        "/<plugin> migrate: the copy starts.",
        placeholders = listOf("from", "type"),
    )

    /** One table copied. */
    public val migrateTable: MessageKey = message(
        "core.migrate.table", "<prefix>Copied <table> (rows: <rows>).",
        "/<plugin> migrate: one table is copied.",
        placeholders = listOf("rows", "table"),
    )

    /** Every table copied and checked, and the config switched. */
    public val migrateDone: MessageKey = message(
        "core.migrate.done", "<prefix><success>Moved the data to <type> and checked it (rows: <rows>).</success> The plugin now uses <type>.",
        "/<plugin> migrate: done; the config's storage type was changed and applied.",
        placeholders = listOf("rows", "type"),
    )

    /** It failed; nothing was switched. */
    public val migrateFailed: MessageKey = message(
        "core.migrate.failed", "<prefix><error>Moving the data to <type> failed: <reason></error> The plugin still uses <from>.",
        "/<plugin> migrate: it failed, and the storage type wasn't changed. <reason> is in English.",
        placeholders = listOf("type", "reason", "from"),
    )

    // /<plugin> forget <player>

    /** No such player. */
    public val forgetUnknown: MessageKey = message(
        "core.forget.unknown", "<prefix><error>No player called <player> has played here.</error>",
        "/<plugin> forget: the name isn't one the server knows. A UUID works too.",
        placeholders = listOf("player"),
    )

    /** Everything about the player was deleted. */
    public val forgetDone: MessageKey = message(
        "core.forget.done", "<prefix><success>Forgot <player>.</success> Records deleted: <records>.",
        "/<plugin> forget: done.",
        placeholders = listOf("player", "records"),
    )

    /** Some of it couldn't be deleted. */
    public val forgetPartly: MessageKey = message(
        "core.forget.partly", "<prefix><error>Some of <player>'s data couldn't be deleted (<failed>).</error> Records deleted: <records>. Run it again.",
        "/<plugin> forget: part of it failed. <failed> lists what, with why, in English.",
        placeholders = listOf("player", "failed", "records"),
    )
}

/**
 * Long lists in chat, a page at a time, with a footer players click for the next page:
 * `/homes`, `/history`, `/vote top`, a lookup's results. Today's `ChatPages`, with the footer a
 * message ([CoreMessages.pagesFooter]) in each reader's language.
 */
public object ChatPages {

    /**
     * One page of a list: its [items], its [number] (from 1), how many [pages] there are (at
     * least 1), and how many items there are in all ([total]).
     */
    public class Page<T>(public val items: List<T>, public val number: Int, public val pages: Int, public val total: Long) {
        public val hasNext: Boolean get() = number < pages
        public val hasPrevious: Boolean get() = number > 1
    }

    /** How many pages [total] items take, at least 1. */
    public fun pages(total: Long, perPage: Int): Int {
        val per = perPage.coerceAtLeast(1)
        return ((total + per - 1) / per).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }

    /** Rows to skip for [page], for a query's `OFFSET`. */
    public fun offset(page: Int, perPage: Int): Long = (page.coerceAtLeast(1) - 1).toLong() * perPage.coerceAtLeast(1)

    /** A page of a list in memory. A page past the end shows the last, and one before the start the first. */
    public fun <T> of(all: List<T>, page: Int, perPage: Int): Page<T> {
        val per = perPage.coerceAtLeast(1)
        val pages = pages(all.size.toLong(), per)
        val number = page.coerceIn(1, pages)
        val from = (number - 1) * per
        return Page(all.subList(from, minOf(all.size, from + per)).toList(), number, pages, all.size.toLong())
    }

    /** A page already read, as from a database with [offset]: [rows] is this page, [total] counted. */
    public fun <T> of(rows: List<T>, total: Long, page: Int, perPage: Int): Page<T> =
        Page(rows.toList(), page, pages(total, perPage), total)

    /**
     * The footer's placeholders for [page] in [language] (its page numbers written for it).
     * [command] is the list's command with `{page}` where the number goes, such as
     * `"/homes {page}"`.
     */
    public fun footer(page: Page<*>, command: String, language: Language): Map<String, Placeholder> = mapOf(
        "page" to Placeholder.text(language.formats.number(page.number)),
        "pages" to Placeholder.text(language.formats.number(page.pages)),
        "next" to Placeholder.text(command.replace("{page}", minOf(page.pages, page.number + 1).toString())),
        "previous" to Placeholder.text(command.replace("{page}", maxOf(1, page.number - 1).toString())),
        "has_next" to Placeholder.section(page.hasNext),
        "has_previous" to Placeholder.section(page.hasPrevious),
    )
}
