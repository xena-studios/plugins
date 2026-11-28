package co.xenastudios.xbackup

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.Argument
import co.xenastudios.core.common.command.ArgumentError
import co.xenastudios.core.common.command.ArgumentType
import co.xenastudios.core.common.command.ArgumentTypes
import co.xenastudios.core.common.command.CommandContext
import co.xenastudios.core.common.command.CommandHandler
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.command.CommandTree
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.xbackup.config.XBackupConfig

/** xBackup's permissions: one per `/backup` subcommand, 1.x's, which operators have. */
public class XBackupPermissions internal constructor() : PermissionSet(XBackupPlugin.PLUGIN_ID) {
    public val now: Permission = permission("now", "Back up now: /backup now.", PermissionDefault.OP)
    public val list: Permission = permission("list", "List backups: /backup list.", PermissionDefault.OP)
    public val status: Permission = permission("status", "See what xBackup is doing: /backup status.", PermissionDefault.OP)
    public val verify: Permission = permission("verify", "Read back stored data and check it: /backup verify.", PermissionDefault.OP)
    public val restore: Permission = permission("restore", "Plan, stage and cancel restores: /backup restore.", PermissionDefault.OP)
    public val prune: Permission = permission("prune", "Remove backups retention doesn't keep: /backup prune.", PermissionDefault.OP)
    public val pin: Permission = permission("pin", "Keep a backup forever: /backup pin.", PermissionDefault.OP)
    public val unpin: Permission = permission("unpin", "Let retention remove a backup again: /backup unpin.", PermissionDefault.OP)
    public val forget: Permission = permission("forget", "Remove one backup now: /backup forget. Also needed to remove a remote's backups with /backup copy <remote> tidy confirm.", PermissionDefault.OP)
    public val copy: Permission = permission("copy", "Copy the backups to a remote now: /backup copy.", PermissionDefault.OP)
    public val link: Permission = permission("link", "Link a Google Drive, OneDrive or Dropbox remote: /backup link.", PermissionDefault.OP)
    public val repair: Permission = permission("repair", "Rebuild the index from the stored data: /backup repair.", PermissionDefault.OP)
}

/** Builds the command tree from the config; each command runs in [Actions], made the first time one runs. */
internal class Commands(private val plugin: XBackupPlugin) {
    val permissions = XBackupPermissions()

    var warnings: List<String> = emptyList()
        private set

    fun tree(config: Config): CommandTree {
        val p = permissions
        val c = XBackupConfig.command
        val problems = ArrayList<String>()
        val tree = commands {
            root(XBackupPlugin.PLUGIN_ID, "Shows xBackup's version, and holds its admin subcommands.", aliases = listOf("xb")) {
                executes(Run(INFO))
                literal("info", "Shows xBackup's version and repository.") { executes(Run(INFO)) }
                mount(plugin.admin)
            }
            if (config[c.enabled]) {
                val aliases = config[c.aliases].filter { it != "backup" && it != XBackupPlugin.PLUGIN_ID && it != "xb" }.distinct()
                if (aliases.size != config[c.aliases].distinct().size) problems += "command.aliases: /backup, /xbackup and /xb are xBackup's own names, so they're left out."
                root("backup", "Run, list, check and restore backups.", aliases = aliases) {
                    literal("now", "Back up now.", p.now) {
                        executes(Run(NOW, optional("reason", ArgumentTypes.text, "Shown in the list.")))
                    }
                    literal("list", "List backups, newest first.", p.list) {
                        executes(Run(LIST, optional("page", ArgumentTypes.integer(1), "The page.")))
                    }
                    literal("status", "What xBackup is doing, and how the last backup went.", p.status) { executes(Run(STATUS)) }
                    literal("verify", "Read back stored data and check it.", p.verify) {
                        executes(Run(VERIFY, optional("full", ArgumentTypes.choice("full"), "Everything, not a sample.")))
                    }
                    literal("restore", "Restore a backup on the next start.", p.restore) {
                        val what = argument("backup", Words(plugin, Words.RESTORE), "A backup's id, latest, or cancel.")
                        executes(Run(RESTORE, what, optional("paths", Words(plugin, Words.PATHS, greedy = true), "Paths to restore, and confirm to stage it.")))
                    }
                    literal("prune", "Remove backups retention doesn't keep, and give back their space.", p.prune) { executes(Run(PRUNE)) }
                    literal("pin", "Keep a backup forever.", p.pin) { executes(Run(PIN, argument("backup", Words(plugin, Words.IDS), "A backup's id, or latest."))) }
                    literal("unpin", "Let retention remove a backup again.", p.unpin) { executes(Run(UNPIN, argument("backup", Words(plugin, Words.IDS), "A backup's id, or latest."))) }
                    literal("forget", "Remove one backup now.", p.forget) {
                        val id = argument("backup", Words(plugin, Words.IDS), "A backup's id, or latest.")
                        executes(Run(FORGET, id, optional("confirm", ArgumentTypes.choice("confirm"), "Remove it.")))
                    }
                    literal("copy", "Copy the backups to a remote now.", p.copy) {
                        val remote = argument("remote", Words(plugin, Words.REMOTES), "A remote's name in config.yml.")
                        executes(Run(COPY, remote, optional("tidy", Words(plugin, Words.TIDY, greedy = true), "tidy, to find what only the remote has; tidy confirm removes it.")))
                    }
                    literal("link", "Link a Google Drive, OneDrive or Dropbox remote.", p.link) {
                        val remote = argument("remote", Words(plugin, Words.REMOTES), "A remote's name in config.yml.")
                        executes(Run(LINK, remote, optional("code", ArgumentTypes.word, "The code Dropbox shows.")))
                    }
                    literal("repair", "Rebuild the index from the stored data.", p.repair) { executes(Run(REPAIR)) }
                }
            }
        }
        warnings = problems
        return tree
    }

    /** Every command's handler, as one class told apart by [id], rather than a lambda each. */
    private inner class Run(private val id: Int, private vararg val args: Argument<*>) : CommandHandler {
        @Suppress("UNCHECKED_CAST")
        private fun <T : Any> arg(ctx: CommandContext, index: Int): T? = ctx.getOrNull(args[index] as Argument<T>)

        override fun run(context: CommandContext) {
            val a = actions()
            when (id) {
                INFO -> a.info(context)
                NOW -> a.now(context, arg(context, 0))
                LIST -> a.list(context, arg(context, 0))
                STATUS -> a.status(context)
                VERIFY -> a.verify(context, arg<String>(context, 0) != null)
                RESTORE -> a.restore(context, arg<String>(context, 0)!!, words(arg(context, 1)))
                PRUNE -> a.prune(context)
                PIN -> a.pin(context, arg<String>(context, 0)!!, true)
                UNPIN -> a.pin(context, arg<String>(context, 0)!!, false)
                FORGET -> a.forget(context, arg<String>(context, 0)!!, arg<String>(context, 1) != null)
                COPY -> a.copy(context, arg<String>(context, 0)!!, words(arg(context, 1)))
                LINK -> a.link(context, arg<String>(context, 0)!!, arg(context, 1))
                REPAIR -> a.repair(context)
            }
        }

        private fun words(text: String?): List<String> = text?.trim()?.split(' ')?.filter { it.isNotEmpty() }.orEmpty()
    }

    private var made: Actions? = null

    private fun actions(): Actions = made ?: Actions(plugin).also { made = it }

    private companion object {
        const val INFO = 0
        const val NOW = 1
        const val LIST = 2
        const val STATUS = 3
        const val VERIFY = 4
        const val RESTORE = 5
        const val PRUNE = 6
        const val PIN = 7
        const val UNPIN = 8
        const val FORGET = 9
        const val COPY = 10
        const val LINK = 11
        const val REPAIR = 12
    }
}

/**
 * A word, completed from what xBackup knows: backup ids, the newest backup's top-level paths, the
 * remotes. Parsing is the command's, which says what's wrong in xBackup's own words.
 */
internal class Words(private val plugin: XBackupPlugin, private val kind: Int, override val greedy: Boolean = false) : ArgumentType<String> {
    override val name: String get() = if (greedy) "text" else "word"

    override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> = Outcome.Ok(input)

    override fun suggest(source: CommandSource): List<String> = try {
        when (kind) {
            IDS -> ids()
            RESTORE -> ids() + "cancel"
            PATHS -> plugin.engine.topLevel + "confirm"
            REMOTES -> plugin.settings.remotes.keys.toList()
            TIDY -> listOf("tidy", "tidy confirm")
            else -> emptyList()
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun ids(): List<String> = listOf("latest") + plugin.engine.snapshots.asReversed().take(29).mapNotNull { it.id?.shortHex() }

    companion object {
        const val IDS = 0
        const val RESTORE = 1
        const val PATHS = 2
        const val REMOTES = 3
        const val TIDY = 4
    }
}
