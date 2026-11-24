package co.xenastudios.core.common.command

import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.module.ModuleStatus
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.global
import co.xenastudios.core.common.text.Component

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
 * `import`, `migrate` and `forget` where the plugin has them ([subcommands]). Each needs
 * `<plugin>.admin.<subcommand>`, and `<plugin>.admin` grants them all; [permissions] declares
 * them, so a plugin lists it beside its own permissions.
 *
 * `reload` runs [reloader] on the global thread, on the next tick (outside the command, since
 * a reload registers commands again), and tells whoever asked how it went. `debug`, `import`,
 * `migrate` and `forget` are declared here, with their arguments and permissions, and say
 * they're not available yet until the features behind them arrive.
 */
public class AdminCommands(
    /** The plugin's id, which the permissions start with. */
    public val pluginId: String,
    /** The plugin's name, as messages show it: `xHomes`. */
    public val pluginName: String,
    private val platform: Platform,
    private val reloader: Reloader,
    /** Which of the subcommands the plugin has, besides `reload` and `debug`, which every plugin has. */
    extra: Set<AdminSubcommand> = emptySet(),
    /** The plugins `import` reads from, such as `litebans`; needed when it has [AdminSubcommand.IMPORT]. */
    private val importSources: List<String> = emptyList(),
) {
    /** The subcommands, in the order they're listed. */
    public val subcommands: List<AdminSubcommand> = AdminSubcommand.entries.filter {
        it == AdminSubcommand.RELOAD || it == AdminSubcommand.DEBUG || it in extra
    }

    init {
        require(AdminSubcommand.IMPORT !in extra || importSources.isNotEmpty()) { "/$pluginId import needs the plugins it imports from" }
        require(AdminSubcommand.IMPORT in extra || importSources.isEmpty()) { "import sources given, but /$pluginId has no import" }
    }

    private val declared = object : PermissionSet(pluginId) {}

    /** `<plugin>.admin`, which grants every admin subcommand's permission. */
    public val admin: Permission

    private val bySubcommand: Map<AdminSubcommand, Permission>

    init {
        val nodes = subcommands.associateWith { "$pluginId.admin.${it.word}" }
        admin = declared.declare("$pluginId.admin", "Every admin subcommand of $pluginName.", PermissionDefault.OP, nodes.values.associateWith { true }, builtIn = true)
        bySubcommand = subcommands.associateWith { sub ->
            declared.declare(nodes.getValue(sub), "Use /$pluginId ${sub.word}.", PermissionDefault.OP, emptyMap(), builtIn = true)
        }
    }

    /** The admin permissions: `<plugin>.admin`, then one per subcommand. */
    public val permissions: PermissionSet get() = declared

    /** The permission [subcommand] needs. */
    public fun permission(subcommand: AdminSubcommand): Permission =
        bySubcommand[subcommand] ?: throw IllegalArgumentException("/$pluginId has no ${subcommand.word}")

    internal fun declare(root: List<String>): List<CommandNode> = subcommands.map { sub ->
        CommandBuilder(root + sub.word, sub.description, permission(sub), emptyList(), builtIn = true).apply {
            when (sub) {
                AdminSubcommand.RELOAD -> executes { context -> reload(context.source) }
                AdminSubcommand.DEBUG -> executes(notYet(sub))
                AdminSubcommand.IMPORT -> {
                    argument("source", ArgumentTypes.choice(*importSources.toTypedArray()), "The plugin to import from.")
                    executes(notYet(sub))
                }
                AdminSubcommand.MIGRATE -> {
                    argument("to", ArgumentTypes.choice("sqlite", "mysql"), "The storage type to move the data to.")
                    confirm()
                    executes(notYet(sub))
                }
                AdminSubcommand.FORGET -> {
                    argument("player", ArgumentTypes.playerName, "The player whose data is deleted, online or not.")
                    confirm()
                    executes(notYet(sub))
                }
            }
        }.build()
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
            source.reply(report?.english(pluginName) ?: Component.text("Reloading $pluginName failed; the console has the details."))
        }
    }

    private fun notYet(sub: AdminSubcommand) = CommandHandler { context ->
        context.fail(Component.text("/$pluginId ${sub.word} isn't available yet."))
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
    /** The report in English, until the translations' messages take over. */
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
