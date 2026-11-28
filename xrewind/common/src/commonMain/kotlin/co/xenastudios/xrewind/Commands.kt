package co.xenastudios.xrewind

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
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.permission.Permission
import co.xenastudios.core.common.permission.PermissionSet
import co.xenastudios.xrewind.query.Params

/** xRewind's permissions: 1.x's, each given to operators by default. */
public class XRewindPermissions internal constructor() : PermissionSet(XRewindPlugin.PLUGIN_ID) {
    public val inspect: Permission = permission("inspect", "Turn the inspector on and off with /xrewind inspect.")
    public val lookup: Permission = permission("lookup", "Search the history: /xrewind lookup, page and near.")
    public val lookupChat: Permission = permission("lookup.chat", "See chat and commands in lookups (a:chat, a:command), which are more private than blocks.")
    public val rollback: Permission = permission("rollback", "Roll back, preview, apply, cancel and undo: /xrewind rollback, apply, cancel and undo.")
    public val rollbackGlobal: Permission = permission("rollback.global", "Roll back past rollback.max-radius, or a whole world (r:#world) or every world (r:#global).")
    public val restore: Permission = permission("restore", "Put back what a rollback undid: /xrewind restore.")
    public val purge: Permission = permission("purge", "Delete old history with /xrewind purge.")
    public val status: Permission = permission("status", "See whether logging is keeping up, with /xrewind status.")
}

/** Builds the command tree, and runs each command (in [Actions], made the first time one runs). */
internal class Commands(private val plugin: XRewindPlugin) {
    lateinit var permissions: XRewindPermissions
        private set

    fun tree(): CommandTree {
        val perms = XRewindPermissions()
        permissions = perms
        val admin = plugin.admin.admin
        return commands {
            root(XRewindPlugin.PLUGIN_ID, "Every change, logged. Any of it, undone: xRewind's commands.", aliases = listOf("xr")) {
                executes(Run(INFO))
                literal("info", "Shows xRewind's version and where its history is kept.", admin) { executes(Run(INFO)) }
                literal("inspect", "Turns the inspector on or off: click a block to see its history.", perms.inspect, listOf("i")) {
                    playerOnly()
                    executes(Run(INSPECT))
                }
                literal("lookup", "Searches the history, newest first, 7 to a page.", perms.lookup, listOf("l")) { params(this, LOOKUP) }
                literal("page", "Another page of your last lookup.", perms.lookup) {
                    executes(Run(PAGE, argument("number", ArgumentTypes.integer(1, Lookups.MAX_PAGE), "Which page.")))
                }
                literal("near", "What changed within 5 blocks in the last 3 days.", perms.lookup) {
                    playerOnly()
                    executes(Run(NEAR))
                }
                literal("rollback", "Undoes matching changes. Needs t:; add #preview to see it first.", perms.rollback, listOf("rb")) { params(this, ROLLBACK) }
                literal("restore", "Puts back what a rollback undid.", perms.restore, listOf("rs")) { params(this, RESTORE) }
                literal("apply", "Applies your preview.", perms.rollback) { executes(Run(APPLY)) }
                literal("cancel", "Drops your preview.", perms.rollback) { executes(Run(CANCEL)) }
                literal("undo", "Reverts your last rollback or restore.", perms.rollback) { executes(Run(UNDO)) }
                literal("purge", "Deletes history older than a time, at least a day: t:30d.", perms.purge) { params(this, PURGE) }
                literal("import", "Imports CoreProtect's history: /xrewind import coreprotect [#again].", admin) {
                    executes(Run(IMPORT, argument("source", ArgumentTypes.choice("coreprotect"), "coreprotect."),
                        optional("again", ArgumentTypes.choice("#again"), "#again imports it a second time.")))
                }
                literal("status", "Where the history is kept, what's saved and waiting, and any problem.", perms.status) { executes(Run(STATUS)) }
                mount(plugin.admin)
            }
        }
    }

    // CoreProtect's parameters: the rest of the line, each word completed from what it can be. One
    // argument, not one a word: Minecraft's Brigadier on Fabric takes no `:` or `#` in a word but the last.
    private fun params(builder: CommandBuilder, id: Int) {
        builder.executes(Run(id, builder.optional("parameters", Word(plugin), "u:, t:, r:, a:, i:, e:, w:, and #preview, #count, #silent.")))
    }

    /** Every command's handler, as one class told apart by [id], rather than a lambda each. */
    private inner class Run(private val id: Int, private vararg val args: Argument<*>) : CommandHandler {
        override fun run(context: CommandContext) {
            val a = actions()
            when (id) {
                INFO -> a.info(context)
                INSPECT -> a.inspect(context)
                PAGE -> a.page(context, context[args[0]] as Int)
                NEAR -> a.near(context)
                APPLY -> plugin.rollbacks.apply(context.source)
                CANCEL -> plugin.rollbacks.cancel(context.source)
                UNDO -> plugin.rollbacks.undo(context.source)
                STATUS -> a.status(context)
                IMPORT -> a.import(context, context.getOrNull(args[1]) != null)
                else -> {
                    val words = args.mapNotNull { context.getOrNull(it)?.toString() }.flatMap { it.split(' ') }.filter { it.isNotBlank() }
                    when (id) {
                        LOOKUP -> a.lookup(context, words)
                        ROLLBACK -> a.rollback(context, words, restore = false)
                        RESTORE -> a.rollback(context, words, restore = true)
                        PURGE -> a.purge(context, words)
                    }
                }
            }
        }
    }

    private var made: Actions? = null

    private fun actions(): Actions = made ?: Actions(plugin).also { made = it }

    private companion object {
        const val INFO = 0
        const val INSPECT = 1
        const val LOOKUP = 2
        const val PAGE = 3
        const val NEAR = 4
        const val ROLLBACK = 5
        const val RESTORE = 6
        const val APPLY = 7
        const val CANCEL = 8
        const val UNDO = 9
        const val PURGE = 10
        const val IMPORT = 11
        const val STATUS = 12
    }
}

/**
 * One of CoreProtect's parameters, completed from what it can be: players after `u:`, actions
 * after `a:`, worlds after `w:`. Reading it is [Params]', which says what's wrong in xRewind's
 * own words.
 */
internal class Word(private val plugin: XRewindPlugin) : ArgumentType<String> {
    override val name: String get() = "parameters"

    override val greedy: Boolean get() = true

    override fun parse(input: String, source: CommandSource): Outcome<String, ArgumentError> = Outcome.Ok(input)

    override fun suggest(source: CommandSource): List<String> = try {
        buildList {
            addAll(listOf("u:", "t:", "r:", "a:", "i:", "e:", "w:", "#preview", "#count", "#silent"))
            plugin.players.names(source).forEach { add("u:$it") }
            Params.ACTIONS.forEach { add("a:$it") }
            plugin.server.worlds().forEach { add("w:$it") }
            addAll(listOf("r:10", "r:#world", "r:#global", "t:1h", "t:1d", "t:1w", "t:30d"))
        }
    } catch (e: Exception) {
        emptyList()
    }
}
