package co.xenastudios.core.common.command

import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.AdminText
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.DebugResult
import co.xenastudios.core.common.admin.ForgetReport
import co.xenastudios.core.common.admin.HealthChecks
import co.xenastudios.core.common.admin.ImportCounts
import co.xenastudios.core.common.admin.ImportListener
import co.xenastudios.core.common.admin.Imports
import co.xenastudios.core.common.admin.MigrationListener
import co.xenastudios.core.common.admin.PlayerDataRegistry
import co.xenastudios.core.common.admin.StorageMigration
import co.xenastudios.core.common.db.DatabaseSettings
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.module.ModuleStatus
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.async
import co.xenastudios.core.common.scheduler.global
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Placeholder

/** The admin subcommands the core gives every plugin (ROADMAP.md, "Admin commands"). */
public enum class AdminSubcommand(
    /** Its word under the plugin's command: `reload`. */
    public val word: String,
    /** What it does, for the docs. */
    public val description: String,
) {
    /** Every plugin. */
    RELOAD("reload", "Reloads config.yml and the language files, and applies them."),

    /** Every plugin. */
    DEBUG("debug", "Checks the database, dependencies and platform, then prints a paste link for support."),

    /** Plugins with importers. */
    IMPORT("import", "Imports data from another plugin."),

    /** Plugins with a database. */
    MIGRATE("migrate", "Moves the data to another storage type."),

    /** Plugins that keep player data. */
    FORGET("forget", "Deletes everything kept about one player."),
}

/**
 * The admin subcommands, built once in the core, that each plugin mounts under its own root
 * command ([CommandBuilder.mount]): `/<plugin> reload` and `/<plugin> debug` always, and
 * `import`, `migrate` and `forget` when the plugin gives what they run on. Each needs
 * `<plugin>.admin.<subcommand>`, and `<plugin>.admin` grants them all, and [updates], the update
 * notices; [permissions] declares them, so a plugin lists it beside its own permissions.
 *
 * - `reload` runs [reloader] on the global thread, on the next tick (outside the command,
 *   since a reload registers commands again), and tells whoever asked how it went.
 * - `debug` runs [debug]'s health checks and shows them, then uploads its report (or writes
 *   it to a file) off the server's threads and gives the link.
 * - `import <source> [--dry-run]` runs one of [imports]' importers, completing their ids.
 * - `migrate <sqlite|mysql> [--force]` moves the data with [migration], once confirmed.
 * - `forget <player>` deletes what [playerData] has about a player, by name or UUID, once
 *   confirmed.
 *
 * Replies are [co.xenastudios.core.common.i18n.CoreMessages], drawn by [text] in the reader's
 * language.
 *
 * [debug] and [playerData] are made the first time `debug` or `forget` runs, not as the plugin
 * starts: few servers ever run them, and their classes (the report, its paste service, the
 * health checks) would otherwise load at every start (decisions/2026-11-25-a-lighter-core.md).
 * Pass each as an `object : () -> Debug { ... }` rather than a lambda: the JVM links a lambda
 * as it's made, loading the class it returns, which an object's method doesn't until it runs.
 */
public class AdminCommands(
    /** The plugin's id, which the permissions start with. */
    public val pluginId: String,
    /** The plugin's name, as messages show it: `xHomes`. */
    public val pluginName: String,
    private val platform: Platform,
    private val reloader: Reloader,
    /** Makes what `debug` checks and reports, once, as it first runs. By default, only that tasks run where they should. */
    private val debug: () -> Debug = object : () -> Debug {
        override fun invoke() = Debug(platform, pluginName, listOf(HealthChecks.threading(platform)))
    },
    /** The plugin's importers, for `import`; `null` if it has none. */
    private val imports: Imports? = null,
    /** How the plugin's database moves, for `migrate`; `null` if it has no database. */
    private val migration: StorageMigration? = null,
    /** Makes what the plugin keeps per player, for `forget`, once, as it first runs; `null` if it keeps nothing. */
    private val playerData: (() -> PlayerDataRegistry)? = null,
    /** Draws the replies for each reader. */
    private val text: AdminText = AdminText.ENGLISH,
) {
    /** The subcommands, in the order they're listed. */
    public val subcommands: List<AdminSubcommand> = AdminSubcommand.entries.filter {
        when (it) {
            AdminSubcommand.RELOAD, AdminSubcommand.DEBUG -> true
            AdminSubcommand.IMPORT -> imports != null
            AdminSubcommand.MIGRATE -> migration != null
            AdminSubcommand.FORGET -> playerData != null
        }
    }

    private val declared = object : PermissionSet(pluginId) {}

    private var report: Debug? = null
    private var registry: PlayerDataRegistry? = null

    // Made on first use. A command runs on its source's thread, so two may race: each makes its
    // own and one is kept, which is harmless.
    private fun report(): Debug = report ?: debug().also { report = it }

    private fun registry(make: () -> PlayerDataRegistry): PlayerDataRegistry = registry ?: make().also { registry = it }

    /** `<plugin>.admin`, which grants every admin subcommand's permission. */
    public val admin: Permission

    private val bySubcommand: Map<AdminSubcommand, Permission>

    /**
     * `<plugin>.admin.update`: told about a newer version as they join (the core's
     * [co.xenastudios.core.common.update.UpdateChecker]). Granted by [admin], not a subcommand.
     */
    public val updates: Permission

    init {
        val nodes = subcommands.associateWith { "$pluginId.admin.${it.word}" }
        val update = "$pluginId.admin.update"
        admin = declared.declare(
            "$pluginId.admin", "Every admin subcommand of $pluginName, and its update notices.", PermissionDefault.OP,
            (nodes.values + update).associateWith { true }, builtIn = true,
        )
        bySubcommand = subcommands.associateWith { sub ->
            declared.declare(nodes.getValue(sub), "Use /$pluginId ${sub.word}.", PermissionDefault.OP, emptyMap(), builtIn = true)
        }
        updates = declared.declare(update, "Hear about newer versions of $pluginName when joining.", PermissionDefault.OP, emptyMap(), builtIn = true)
    }

    /** The admin permissions: `<plugin>.admin`, one per subcommand, then `<plugin>.admin.update`. */
    public val permissions: PermissionSet get() = declared

    /** The permission [subcommand] needs. */
    public fun permission(subcommand: AdminSubcommand): Permission =
        bySubcommand[subcommand] ?: throw IllegalArgumentException("/$pluginId has no ${subcommand.word}")

    internal fun declare(root: List<String>): List<CommandNode> = subcommands.map { sub ->
        CommandBuilder(root + sub.word, sub.description, permission(sub), emptyList(), builtIn = true).apply {
            when (sub) {
                AdminSubcommand.RELOAD, AdminSubcommand.DEBUG -> executes(Handler(sub))
                AdminSubcommand.IMPORT -> {
                    val source = argument("source", ImportSource(imports!!), "The plugin to import from.")
                    val dryRun = optional("dry-run", ArgumentTypes.choice(DRY_RUN), "Counts what would be imported, changing nothing.")
                    executes(Handler(sub, source, dryRun))
                }
                AdminSubcommand.MIGRATE -> {
                    checkNotNull(migration)
                    val to = argument("to", ArgumentTypes.choice("sqlite", "mysql"), "The storage type to move the data to.")
                    val force = optional("force", ArgumentTypes.choice(FORCE), "Replaces what the target database already has.")
                    confirm()
                    executes(Handler(sub, to, force))
                }
                AdminSubcommand.FORGET -> {
                    checkNotNull(playerData)
                    val player = argument("player", ArgumentTypes.playerNameOrUuid, "The player whose data is deleted, online or not.")
                    confirm()
                    executes(Handler(sub, player))
                }
            }
        }.build()
    }

    /**
     * Each subcommand's handler, one class told apart by [sub] rather than a lambda each: the JVM
     * makes a class of every lambda where it's first made, as each plugin starts
     * (decisions/2026-11-25-a-lighter-core.md). [args] are the subcommand's own arguments, in order.
     */
    private inner class Handler(private val sub: AdminSubcommand, private vararg val args: Argument<String>) : CommandHandler {
        override fun run(context: CommandContext) {
            when (sub) {
                AdminSubcommand.RELOAD -> reload(context.source)
                AdminSubcommand.DEBUG -> debug(context.source)
                AdminSubcommand.IMPORT -> import(context, imports!!, context[args[0]], context.getOrNull(args[1]) != null)
                AdminSubcommand.MIGRATE -> migrate(context, migration!!, context[args[0]], context.getOrNull(args[1]) != null)
                AdminSubcommand.FORGET -> forget(context.source, registry(playerData!!), context[args[0]])
            }
        }
    }

    private fun reply(source: CommandSource, message: MessageKey, vararg placeholders: Pair<String, Any>) {
        text.render(source, message, placeholders.toMap())?.let(source::reply)
    }

    private fun reload(source: CommandSource) {
        platform.scheduler.global {
            val report = try {
                reloader.reload()
            } catch (e: Throwable) {
                if (isFatal(e)) throw e
                platform.log.error("Reloading $pluginName failed", e)
                null
            }
            if (report == null) {
                reply(source, CoreMessages.reloadFailed, "plugin" to pluginName)
                return@global
            }
            val count = report.on.size
            reply(
                source, CoreMessages.reloadDone, "plugin" to pluginName, "count" to count,
                "has_one" to Placeholder.section(count == 1), "has_many" to Placeholder.section(count != 1),
            )
            report.problems.forEach { reply(source, CoreMessages.reloadProblem, "problem" to it) }
            report.failed.forEach { (key, failure) -> reply(source, CoreMessages.reloadModuleOff, "module" to key, "reason" to failure.reason) }
        }
    }

    private fun debug(source: CommandSource) {
        reply(source, CoreMessages.debugChecking, "plugin" to pluginName)
        val debug = report()
        debug.check().onSettled { results, _ ->
            val health = results.orEmpty()
            for (result in health) text.render(source, result.message, result.placeholders)?.let(source::reply)
            platform.scheduler.async {
                val published = try {
                    debug.publish(debug.report(health))
                } catch (e: Throwable) {
                    if (isFatal(e)) throw e
                    platform.log.error("The debug report of $pluginName failed", e)
                    pendingOf(DebugResult.Failed("it threw ${e::class.simpleName}: ${e.message}"))
                }
                published.onSettled { result, error ->
                    when (result) {
                        is DebugResult.Uploaded -> reply(source, CoreMessages.debugUploaded, "url" to result.url)
                        is DebugResult.Saved ->
                            if (result.uploadError == null) reply(source, CoreMessages.debugSavedLocally, "file" to result.file)
                            else reply(source, CoreMessages.debugSaved, "reason" to result.uploadError, "file" to result.file)
                        is DebugResult.Failed -> reply(source, CoreMessages.debugFailed, "reason" to result.reason)
                        null -> reply(source, CoreMessages.debugFailed, "reason" to error.toString())
                    }
                }
            }
        }
    }

    private fun import(context: CommandContext, imports: Imports, id: String, dryRun: Boolean) {
        val source = context.source
        val name = imports[id]?.displayName ?: id
        val started = imports.start(id, dryRun, object : ImportListener {
            override fun notFound() = reply(source, CoreMessages.importNotFound, "source" to name)

            override fun started(total: Long, done: Long, resumed: Boolean) =
                if (resumed) reply(source, CoreMessages.importResumed, "source" to name, "done" to done, "total" to total)
                else reply(source, CoreMessages.importStarted, "source" to name, "total" to total)

            override fun progress(done: Long, total: Long) =
                reply(source, CoreMessages.importProgress, "source" to name, "done" to done, "total" to total)

            override fun recordFailed(record: String, reason: String) =
                reply(source, CoreMessages.importRecordFailed, "record" to record, "reason" to reason)

            override fun finished(counts: ImportCounts, dryRun: Boolean) =
                if (dryRun) {
                    reply(source, CoreMessages.importDryRun, "source" to name, "importable" to counts.imported, "total" to counts.read, "skipped" to counts.skipped)
                } else {
                    reply(source, CoreMessages.importDone, "source" to name, "imported" to counts.imported, "skipped" to counts.skipped, "failed" to counts.failed)
                }

            override fun stopped(reason: String) = reply(source, CoreMessages.importStopped, "source" to name, "reason" to reason)
        })
        if (!started) context.fail(text.render(source, CoreMessages.importBusy, emptyMap()) ?: Component.EMPTY)
    }

    private fun migrate(context: CommandContext, migration: StorageMigration, to: String, force: Boolean) {
        val source = context.source
        val type = if (to == "mysql") DatabaseSettings.Type.MYSQL else DatabaseSettings.Type.SQLITE
        val from = migration.currentType
        if (type == from) context.fail(text.render(source, CoreMessages.migrateSame, mapOf("type" to label(type))) ?: Component.EMPTY)
        val started = migration.run(type, force, object : MigrationListener {
            override fun notEmpty(rows: Map<String, Long>) = reply(
                source, CoreMessages.migrateNotEmpty, "type" to label(type),
                "tables" to rows.entries.joinToString(", ") { "${it.key}: ${it.value}" },
            )

            override fun started() = reply(source, CoreMessages.migrateStarted, "from" to label(from), "type" to label(type))

            override fun copied(table: String, rows: Long) = reply(source, CoreMessages.migrateTable, "rows" to rows, "table" to table)

            override fun finished(rows: Long) = reply(source, CoreMessages.migrateDone, "rows" to rows, "type" to label(type))

            override fun failed(reason: String) =
                reply(source, CoreMessages.migrateFailed, "type" to label(type), "reason" to reason, "from" to label(from))
        })
        if (!started) context.fail(text.render(source, CoreMessages.migrateBusy, emptyMap()) ?: Component.EMPTY)
    }

    private fun forget(source: CommandSource, playerData: PlayerDataRegistry, who: String) {
        playerData.resolve(who).onSettled { player, error ->
            if (player == null) {
                if (error != null) platform.log.error("Looking up $who for /$pluginId forget failed", error)
                reply(source, CoreMessages.forgetUnknown, "player" to who)
                return@onSettled
            }
            // The plugin's data may be files or a database: deleting it waits, so not here.
            platform.scheduler.async {
                playerData.forget(player).onSettled { report, failure ->
                    val result = report ?: ForgetReport(0, mapOf("everything" to failure.toString()))
                    if (result.failed.isEmpty()) {
                        platform.log.info("${source.name} had $pluginName forget $who (${player.uuid}): ${result.records} records deleted.")
                        reply(source, CoreMessages.forgetDone, "player" to who, "records" to result.records)
                    } else {
                        platform.log.warn("Forgetting $who (${player.uuid}) in $pluginName failed in part: ${result.failed}")
                        reply(
                            source, CoreMessages.forgetPartly, "player" to who, "records" to result.records,
                            "failed" to result.failed.entries.joinToString("; ") { "${it.key}: ${it.value}" },
                        )
                    }
                }
            }
        }
    }

    /** `import`'s first argument: the id of a registered importer, completed from them. */
    private class ImportSource(private val imports: Imports) : ArgumentType<String> {
        override val name: String get() = "choice"
        override val choices: List<String> get() = imports.ids

        override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> =
            imports.ids.firstOrNull { it.equals(input, ignoreCase = true) }?.let { Outcome.Ok(it) }
                ?: Outcome.Err(ArgumentError.NotAChoice(imports.ids))
    }

    private companion object {
        const val DRY_RUN = "--dry-run"
        const val FORCE = "--force"

        fun label(type: DatabaseSettings.Type): String = when (type) {
            DatabaseSettings.Type.SQLITE -> "SQLite"
            DatabaseSettings.Type.MYSQL -> "MySQL"
        }
    }
}

/**
 * Reloads a plugin, for `/<plugin> reload`: reads its config and language files again and
 * applies them. Called on the global thread.
 */
public fun interface Reloader {
    /** Reloads, and says how it went. */
    public fun reload(): ReloadReport
}

/**
 * How a reload went: [problems] with the files (each already logged), the modules [on] after
 * it, and those that [failed] with why.
 */
public class ReloadReport(
    public val problems: List<String>,
    public val on: List<String>,
    public val failed: Map<String, ModuleStatus.Failed>,
) {
    /** The report in English, as plain lines; `/<plugin> reload` draws it from the lang files ([CoreMessages.reloadDone]). */
    public fun english(pluginName: String): Component {
        val lines = mutableListOf("Reloaded $pluginName: ${on.size} ${if (on.size == 1) "module" else "modules"} on.")
        problems.forEach { lines += "Problem: $it" }
        failed.forEach { (key, failure) -> lines += "Module $key is off: ${failure.reason}" }
        return Component.text(lines.joinToString("\n"))
    }
}

/**
 * The usual reload: [reloadFiles] reads the config and language files again (and returns the
 * problems found), then [modules] is synced to the modules the new config [wanted]s: new ones
 * turned on, gone ones off, the rest reloaded in place or replaced ([Module.reload]). The
 * plugin rebuilds its command tree and syncs its [CommandRegistrar] in [reloadFiles] too, so
 * command changes apply.
 */
public class ModuleReloader(
    private val modules: ModuleSet,
    private val reloadFiles: () -> List<String>,
    private val wanted: () -> Map<String, () -> Module>,
) : Reloader {
    override fun reload(): ReloadReport {
        val problems = reloadFiles()
        val now = wanted()
        modules.sync(now)
        // Only the modules still wanted: one that failed before and is now off by config isn't news.
        return ReloadReport(problems, modules.active, modules.failed.filterKeys { it in now })
    }
}
