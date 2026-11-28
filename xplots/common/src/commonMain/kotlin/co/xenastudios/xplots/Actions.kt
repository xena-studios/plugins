package co.xenastudios.xplots

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandContext
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.locked
import co.xenastudios.xplots.api.EndReason
import co.xenastudios.xplots.config.XPlotsConfig
import co.xenastudios.xplots.model.Facing
import co.xenastudios.xplots.model.Field
import co.xenastudios.xplots.model.Layout
import co.xenastudios.xplots.model.Plot
import co.xenastudios.xplots.model.SignPlacement
import co.xenastudios.xplots.model.SignSpot
import co.xenastudios.xplots.model.Settings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** Thrown to end a command with a message. */
private class Stop(val key: MessageKey, val placeholders: Array<out Pair<String, Any>>) : RuntimeException()

/** What the commands do: made the first time one runs, so none of it loads as the server starts. */
internal class Actions(private val plugin: XPlotsPlugin) {
    private val texts get() = plugin.texts

    fun run(id: Int, context: CommandContext, args: List<Any?>) {
        val source = context.source
        try {
            if (id != Commands.INFO && !plugin.isReady) stop(XPlotsMessages.loading)
            when (id) {
                Commands.MINE -> plugin.ui.mine(context.player)
                Commands.ADD -> add(context.player, args[0] as String)
                Commands.REMOVE -> remove(context.player, args[0] as String)
                Commands.HOME -> home(context.player, args[0] as String?)
                Commands.WARP -> warp(context.player, args[0] as String?)
                Commands.ROLLBACK_OWN -> rollbackOwn(context.player, args[0] as String, args[1] as Duration?)
                Commands.INFO -> info(source)
                Commands.WAND -> wand(context.player)
                Commands.SELECT -> select(source, args[0] as String?)
                Commands.FIELD_CREATE -> fieldCreate(source, args[0] as String, args[1] as String)
                Commands.FIELD_DELETE -> fieldDelete(source, field(args[0] as String))
                Commands.FIELD_LIST -> fieldList(source)
                Commands.FIELD_INFO -> fieldInfo(source, field(args[0] as String))
                Commands.FIELD_SET -> fieldSet(source, field(args[0] as String), args[1] as String, args[2] as String)
                Commands.FIELD_SIGNS -> fieldSigns(source, field(args[0] as String))
                Commands.FIELD_WARP -> fieldWarp(source, field(args[0] as String), args[1] != null)
                Commands.PLOT_CREATE -> plotCreate(source, field(args[0] as String))
                Commands.PLOT_DELETE -> plot(source, args[0] as String?).let {
                    plugin.builder.deletePlot(it)
                    reply(source, XPlotsMessages.plotDeleted, "plot" to it.id)
                }
                Commands.PLOT_INFO -> plotInfo(source, plot(source, args[0] as String?))
                Commands.PLOT_EVICT -> plotEvict(source, plot(source, args[0] as String?))
                Commands.PLOT_RESET -> plot(source, args[0] as String?).let { plugin.resets.reset(it, Replied(plugin, source, it)) }
                Commands.PLOT_OWNER -> plotOwner(source, plot(source, args[0] as String), known(args[1] as String))
                Commands.PLOT_ADD -> plot(source, args[0] as String).let { p ->
                    if (p.isFree) stop(XPlotsMessages.notRented, "plot" to p.id)
                    val who = known(args[1] as String)
                    plugin.rentals.addMember(SourceTeller(plugin, source), source.player, p, who.player, who.name, staff = true)
                }
                Commands.PLOT_REMOVE -> plot(source, args[0] as String).let { p ->
                    plugin.rentals.removeMember(SourceTeller(plugin, source), source.player, p, known(args[1] as String).player, staff = true)
                }
                Commands.PLOT_ROLLBACK -> rollback(source, plot(source, args[0] as String), args[2] as String, args[1] as Duration)
                Commands.CONFIRM -> if (!plugin.confirms.confirm(source.name)) stop(XPlotsMessages.confirmNothing)
                Commands.CANCEL -> {
                    if (!plugin.confirms.cancel(source.name)) stop(XPlotsMessages.confirmNothing)
                    reply(source, XPlotsMessages.cancelled)
                }
            }
        } catch (e: Stop) {
            reply(source, e.key, *e.placeholders)
        }
    }

    private fun stop(message: MessageKey, vararg placeholders: Pair<String, Any>): Nothing = throw Stop(message, placeholders)

    private fun reply(source: CommandSource, message: MessageKey, vararg placeholders: Pair<String, Any>) = texts.reply(source, message, *placeholders)

    private fun known(name: String): Known = plugin.server.known(name) ?: stop(XPlotsMessages.neverJoined, "player" to name)

    // ---- players ----

    /** The plot the player means: the one of theirs they stand in, or their only one. */
    private fun chosen(player: PlayerRef): Plot {
        val owned = plugin.plots.owned(player)
        if (owned.isEmpty()) stop(XPlotsMessages.noPlots)
        if (owned.size == 1) return owned[0]
        val here = standing(player)
        if (here != null && here in owned) return here
        stop(XPlotsMessages.whichPlot)
    }

    private fun standing(player: PlayerRef): Plot? {
        val at = plugin.server.position(player) ?: return null
        return plugin.plots.at(at.world.name, at.blockX, at.blockY, at.blockZ)?.takeIf { it !== Field.PATH }
    }

    private fun add(player: PlayerRef, name: String) {
        val plot = chosen(player)
        val who = known(name)
        plugin.rentals.addMember(PlayerTeller(plugin, player), player, plot, who.player, who.name, staff = false)
    }

    private fun remove(player: PlayerRef, name: String) {
        val plot = chosen(player)
        val member = plot.lease?.members?.entries?.lastOrNull { it.value.equals(name, ignoreCase = true) }?.key
            ?: stop(XPlotsMessages.notMember, "player" to name)
        plugin.rentals.removeMember(PlayerTeller(plugin, player), player, plot, member, staff = false)
    }

    private fun home(player: PlayerRef, id: String?) {
        val plot = if (id == null) {
            plugin.plots.owned(player).firstOrNull() ?: stop(XPlotsMessages.noPlots)
        } else {
            plugin.plots.plot(id.lowercase())?.takeIf { plugin.rentals.owns(player, it) } ?: stop(XPlotsMessages.notYours)
        }
        plugin.ui.home(player, plot)
    }

    private fun warp(player: PlayerRef, name: String?) {
        val field = if (name == null) {
            val warps = plugin.plots.fields().filter { it.warp != null }.sortedBy { it.name }
            if (warps.size != 1) stop(co.xenastudios.core.common.i18n.CoreMessages.commandUsage, "usage" to "/plots warp <${warps.joinToString("|") { it.name }}>")
            warps[0]
        } else {
            plugin.plots.field(name) ?: stop(XPlotsMessages.noWarp, "field" to name)
        }
        val to = decode(field.worldRef, field.warp) ?: stop(XPlotsMessages.noWarp, "field" to field.name)
        if (!plugin.server.hasWorld(field.world)) stop(XPlotsMessages.noWarp, "field" to field.name)
        plugin.teleports.teleport(player, to)
    }

    private fun rollbackOwn(player: PlayerRef, name: String, time: Duration?) {
        val plot = chosen(player)
        rollback(SourceOf(plugin, player), plot, name, time ?: 1.days)
    }

    private fun rollback(source: CommandSource, plot: Plot, name: String, time: Duration) {
        val rewind = plugin.hooks.rewind() ?: stop(XPlotsMessages.rollbackUnavailable)
        val seconds = maxOf(1L, time.inWholeSeconds)
        reply(source, XPlotsMessages.rollbackWorking)
        rewind.rollback(plot.field.world, plot.box, name, seconds).onSettled { result, error ->
            when (result) {
                is Outcome.Ok -> if (result.value == 0) {
                    reply(source, XPlotsMessages.rollbackNone, "player" to name, "plot" to plot.id)
                } else {
                    reply(source, XPlotsMessages.rollbackDone, "count" to result.value.toString(), "player" to name, "plot" to plot.id)
                }
                is Outcome.Err -> reply(source, XPlotsMessages.rollbackFailed, "reason" to result.error)
                null -> reply(source, XPlotsMessages.rollbackFailed, "reason" to (error?.message ?: "it failed"))
            }
        }
    }

    // ---- staff ----

    private fun info(source: CommandSource) {
        val fields = plugin.plots.fields()
        reply(
            source, XPlotsMessages.info, "version" to plugin.version, "platform" to plugin.platform.name,
            "fields" to fields.size.toString(), "plots" to fields.sumOf { it.plots().size }.toString(),
            "storage" to (plugin.database?.description ?: plugin.failure ?: "nothing yet"),
            "rent" to (plugin.rentals.wallet.item()?.let { "the item ${it.material}" } ?: plugin.hooks.economy()?.name ?: "no economy yet"),
        )
    }

    private fun wand(player: PlayerRef) {
        val name = texts.render(player, XPlotsMessages.wandName) ?: co.xenastudios.core.common.text.Component.text("xPlots wand")
        val lore = texts.render(player, XPlotsMessages.wandLore)?.let(::splitLines).orEmpty()
        plugin.server.giveWand(player, name, lore)
        texts.send(player, XPlotsMessages.wandGiven)
    }

    private fun select(source: CommandSource, corners: String?) {
        val words = corners?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }.orEmpty()
        if (words.isEmpty()) {
            val player = source.player ?: stop(XPlotsMessages.noWorldEdit)
            val (world, box) = plugin.server.worldEditSelection(player) ?: stop(XPlotsMessages.noWorldEdit)
            plugin.selections.corner(source.name, 1, world, box.minX, box.minY, box.minZ)
            plugin.selections.corner(source.name, 2, world, box.maxX, box.maxY, box.maxZ)
            return reply(source, XPlotsMessages.selected, "width" to box.width.toString(), "height" to box.height.toString(), "depth" to box.depth.toString(), "world" to world)
        }
        if (words.size != 7) stop(co.xenastudios.core.common.i18n.CoreMessages.commandUsage, "usage" to "/xplots select <world> <x1> <y1> <z1> <x2> <y2> <z2>")
        val world = words[0]
        if (!plugin.server.hasWorld(world)) stop(XPlotsMessages.noWorld, "world" to world)
        val c = IntArray(6) { number(words[it + 1], -30_000_000, 30_000_000) }
        plugin.selections.corner(source.name, 1, world, c[0], c[1], c[2])
        val box = plugin.selections.corner(source.name, 2, world, c[3], c[4], c[5]).box!!
        reply(source, XPlotsMessages.selected, "width" to box.width.toString(), "height" to box.height.toString(), "depth" to box.depth.toString(), "world" to world)
    }

    private fun number(raw: String, min: Int, max: Int): Int {
        val n = raw.toIntOrNull()
        if (n == null || n < min || n > max) stop(XPlotsMessages.badNumber, "value" to raw, "min" to min.toString(), "max" to max.toString())
        return n
    }

    private fun selection(source: CommandSource): Selections.Selection {
        val s = plugin.selections[source.name]
        if (s?.box == null) stop(XPlotsMessages.noSelection)
        if (!plugin.server.hasWorld(s.world)) stop(XPlotsMessages.selectionWorldGone)
        return s
    }

    private fun field(name: String): Field = plugin.plots.field(name) ?: stop(XPlotsMessages.noField, "field" to name)

    private fun fieldCreate(source: CommandSource, raw: String, options: String) {
        val name = raw.lowercase()
        var sizeX = 0
        var sizeZ = 0
        var path = 1
        val usage = "/xplots field create <name> size:<n or WxD> [path:<n>]"
        for (word in options.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            when {
                word.startsWith("size:") -> {
                    val parts = word.removePrefix("size:").split('x')
                    if (parts.size > 2) stop(co.xenastudios.core.common.i18n.CoreMessages.commandUsage, "usage" to usage)
                    sizeX = number(parts[0], 1, 256)
                    sizeZ = if (parts.size == 2) number(parts[1], 1, 256) else sizeX
                }
                word.startsWith("path:") -> path = number(word.removePrefix("path:"), 0, 64)
                else -> stop(co.xenastudios.core.common.i18n.CoreMessages.commandUsage, "usage" to usage)
            }
        }
        if (sizeX == 0) stop(co.xenastudios.core.common.i18n.CoreMessages.commandUsage, "usage" to usage)
        val selection = selection(source)
        val world = selection.world
        val layout = Layout.of(selection.box!!, sizeX, sizeZ, path)
        plugin.builder.problem(name, world, layout)?.let { stop(XPlotsMessages.problem, "reason" to word(source, it)) }
        layout!!
        val player = source.player
        val preview = if (player != null) Preview(plugin, player, world, layout, XPlotsConfig.signs.placement(plugin.config)) else null
        plugin.confirms.put(source.name, CreateField(this, plugin, source, name, world, layout, preview))
        reply(
            source, XPlotsMessages.previewed, "cols" to layout.cols.toString(), "rows" to layout.rows.toString(), "count" to layout.count.toString(),
            "size" to "$sizeX×$sizeZ", "path" to path.toString(),
            "outlined" to (if (player != null) texts.word(player, XPlotsMessages.outlined) else ""),
        )
    }

    private class CreateField(
        private val actions: Actions,
        private val plugin: XPlotsPlugin,
        private val source: CommandSource,
        private val name: String,
        private val world: String,
        private val layout: Layout,
        private val preview: Preview?,
    ) : Confirms.Action {
        override fun confirm() {
            // Checked again: something may have changed in the two minutes since.
            plugin.builder.problem(name, world, layout)?.let {
                plugin.texts.reply(source, XPlotsMessages.problem, "reason" to actions.word(source, it))
                return
            }
            plugin.texts.reply(source, XPlotsMessages.making, "field" to name)
            plugin.builder.createField(name, world, layout, Made(plugin, source, name, layout.count))
        }

        override fun dismiss() {
            preview?.stop()
        }
    }

    private class Made(private val plugin: XPlotsPlugin, private val source: CommandSource, private val name: String, private val count: Int) : () -> Unit {
        override fun invoke() = plugin.texts.reply(source, XPlotsMessages.made, "field" to name, "count" to count.toString())
    }

    /** A [Problem] as plain words, for `<reason>`. */
    fun word(source: CommandSource, problem: Problem): String =
        texts.render(source.player, problem.message, *problem.placeholders)?.plain() ?: problem.message.english

    private fun fieldDelete(source: CommandSource, field: Field) {
        val rented = field.plots().count { !it.isFree }
        plugin.confirms.put(source.name, DeleteField(plugin, source, field))
        val rentedText = if (rented > 0) texts.render(source.player, XPlotsMessages.deleteRented, "count" to rented.toString()) ?: "" else ""
        reply(source, XPlotsMessages.deleteAsk, "field" to field.name, "count" to field.plots().size.toString(), "rented" to rentedText)
    }

    private class DeleteField(private val plugin: XPlotsPlugin, private val source: CommandSource, private val field: Field) : Confirms.Action {
        override fun confirm() {
            if (plugin.plots.field(field.name) !== field) return
            plugin.builder.deleteField(field)
            plugin.texts.reply(source, XPlotsMessages.deleted, "field" to field.name)
        }
    }

    private fun fieldList(source: CommandSource) {
        val fields = plugin.plots.fields().sortedBy { it.name }
        if (fields.isEmpty()) stop(XPlotsMessages.noFields)
        reply(source, XPlotsMessages.fieldsHeader)
        for (field in fields) {
            val plots = field.plots()
            reply(source, XPlotsMessages.fieldLine, "field" to field.name, "world" to field.world, "rented" to plots.count { !it.isFree }.toString(), "count" to plots.size.toString())
        }
    }

    private fun fieldInfo(source: CommandSource, field: Field) {
        val s = field.settings(plugin.rentals.defaults())
        val a = field.layout.area
        val plots = field.plots()
        reply(
            source, XPlotsMessages.fieldInfo, "field" to field.name, "world" to field.world, "x" to a.minX.toString(), "y" to a.minY.toString(), "z" to a.minZ.toString(),
            "count" to plots.size.toString(), "size" to "${field.layout.sizeX}×${field.layout.sizeZ}", "rented" to plots.count { !it.isFree }.toString(),
        )
        val placement = field.signPlacement(XPlotsConfig.signs.placement(plugin.config))
        val wallet = plugin.rentals.wallet
        val rows = listOf(
            "price" to wallet.format(s.price),
            "period" to plugin.rentals.time(s.period.inWholeMilliseconds),
            "max-prepaid" to plugin.rentals.time(s.maxPrepaid.inWholeMilliseconds),
            "starting-slots" to s.startingSlots.toString(),
            "max-slots" to s.maxSlots.toString(),
            "slot-price" to wallet.format(s.slotPrice),
            "reset" to s.reset.toString(),
            "abandon-refund" to "${s.abandonRefund}%",
            "sign-position" to SignPlacement.name(placement.position),
            "sign-rotation" to SignPlacement.name(placement.rotation),
            "auto-renew" to s.autoRenew.toString(),
        )
        val own = texts.word(source.player, XPlotsMessages.own)
        for ((key, value) in rows) reply(source, XPlotsMessages.settingLine, "key" to key, "value" to value, "own" to (if (key in field.overrides) own else ""))
        reply(source, XPlotsMessages.warpLine, "warp" to texts.word(source.player, if (field.warp == null) XPlotsMessages.noMembers else XPlotsMessages.warpSetWord))
    }

    private fun fieldSet(source: CommandSource, field: Field, raw: String, value: String) {
        val key = raw.lowercase()
        if (key !in Settings.KEYS) stop(XPlotsMessages.notASetting, "key" to key, "keys" to Settings.KEYS.joinToString(", "))
        if (value.equals("default", ignoreCase = true)) {
            locked(plugin.lock) { field.overrides = field.overrides - key }
            reply(source, XPlotsMessages.setDefault, "field" to field.name, "key" to key)
        } else {
            when (val checked = Settings.check(key, value)) {
                is Outcome.Ok -> {
                    locked(plugin.lock) { field.overrides = field.overrides + (key to checked.value) }
                    reply(source, XPlotsMessages.set, "field" to field.name, "key" to key, "value" to checked.value)
                }
                is Outcome.Err -> stop(XPlotsMessages.badSetting, "key" to key, "reason" to checked.error)
            }
        }
        plugin.store?.updateField(field)
        if (key in Settings.SIGN_KEYS) {
            fieldSigns(source, field)
        } else {
            // Prices and periods show on signs.
            field.plots().forEach(plugin.signs::update)
            plugin.expiry.reschedule()
        }
    }

    private fun fieldSigns(source: CommandSource, field: Field) {
        val moved = plugin.builder.placeSigns(field)
        val p = field.signPlacement(XPlotsConfig.signs.placement(plugin.config))
        val placement = "${SignPlacement.name(p.position)}, ${SignPlacement.name(p.rotation)}"
        if (moved == 0) reply(source, XPlotsMessages.signsSame, "field" to field.name, "placement" to placement)
        else reply(source, XPlotsMessages.signsMoved, "count" to moved.toString(), "field" to field.name, "placement" to placement)
    }

    private fun fieldWarp(source: CommandSource, field: Field, remove: Boolean) {
        if (remove) {
            field.warp = null
            reply(source, XPlotsMessages.warpRemoved, "field" to field.name)
        } else {
            val player = source.player ?: stop(co.xenastudios.core.common.i18n.CoreMessages.commandPlayersOnly)
            val at = plugin.server.position(player) ?: return
            if (at.world.name != field.world) stop(XPlotsMessages.warpWorld, "world" to field.world)
            field.warp = encode(at)
            reply(source, XPlotsMessages.warpSet, "field" to field.name)
        }
        plugin.store?.updateField(field)
    }

    /** The plot named [id], or the one the player stands in. */
    private fun plot(source: CommandSource, id: String?): Plot {
        if (id == null) {
            source.player?.let(::standing)?.let { return it }
            stop(co.xenastudios.core.common.i18n.CoreMessages.commandUsage, "usage" to "/xplots plot <subcommand> <plot>")
        }
        return plugin.plots.plot(id.lowercase()) ?: stop(XPlotsMessages.noPlot, "plot" to id)
    }

    private fun plotCreate(source: CommandSource, field: Field) {
        val selection = selection(source)
        val box = selection.box!!
        plugin.builder.plotProblem(field, selection.world, box)?.let { stop(XPlotsMessages.problem, "reason" to word(source, it)) }
        val plot = plugin.builder.createPlot(field, box)
        reply(source, XPlotsMessages.plotMade, "plot" to plot.id)
    }

    private fun plotInfo(source: CommandSource, plot: Plot) {
        val b = plot.box
        val lease = plot.lease
        val viewer = source.player
        val restParts = ArrayList<co.xenastudios.core.common.text.Component>()
        if (lease == null) {
            texts.render(viewer, XPlotsMessages.plotFree)?.let(restParts::add)
        } else {
            val p = plugin.rentals.placeholders(plot, viewer)
            texts.render(viewer, XPlotsMessages.plotRented, *p, "renews" to plugin.renewals.describe(plot))?.let(restParts::add)
        }
        if (plugin.isResetting(plot)) texts.render(viewer, XPlotsMessages.plotResetting)?.let(restParts::add)
        reply(
            source, XPlotsMessages.plotInfo, "plot" to plot.id, "size" to b.size, "from" to "${b.minX} ${b.minY} ${b.minZ}", "to" to "${b.maxX} ${b.maxY} ${b.maxZ}",
            "rest" to co.xenastudios.core.common.text.Component("", children = restParts),
        )
    }

    private fun plotEvict(source: CommandSource, plot: Plot) {
        val owner = plot.lease?.ownerName ?: stop(XPlotsMessages.notRented, "plot" to plot.id)
        if (!plugin.rentals.end(plot, EndReason.EVICTED)) stop(XPlotsMessages.refused)
        reply(source, XPlotsMessages.evicted, "owner" to owner, "plot" to plot.id)
    }

    private fun plotOwner(source: CommandSource, plot: Plot, who: Known) {
        if (!plugin.rentals.setOwner(plot, who.player, who.name).isDone) stop(XPlotsMessages.refused)
        val lease = plot.lease ?: return
        reply(source, XPlotsMessages.ownerSet, "plot" to plot.id, "owner" to lease.ownerName, "time" to plugin.rentals.time(lease.remaining(plugin.now())))
    }

    private class Replied(private val plugin: XPlotsPlugin, private val source: CommandSource, private val plot: Plot) : () -> Unit {
        override fun invoke() {
            plugin.signs.update(plot)
            plugin.texts.reply(source, XPlotsMessages.resetDone, "plot" to plot.id)
        }
    }

    /** A player as a command source, for replies from code that takes one. */
    private class SourceOf(private val plugin: XPlotsPlugin, override val player: PlayerRef) : CommandSource {
        override val name: String get() = plugin.server.name(player) ?: player.toString()
        override fun hasPermission(node: String): Boolean = plugin.server.hasPermission(player, node)
        override fun reply(message: co.xenastudios.core.common.text.Component) = plugin.server.send(player, message)
    }

    // ---- the wand: on the player's thread, from the adapter's click ----

    /** The wand was used on a block: corners, signs moved and turned, a plot's staff menu. */
    fun wand(player: PlayerRef, name: String, left: Boolean, sneaking: Boolean, world: String, x: Int, y: Int, z: Int, faceX: Int, faceY: Int, faceZ: Int) {
        if (!plugin.isReady) return texts.send(player, XPlotsMessages.loading)
        val plots = plugin.plots
        when {
            left && sneaking -> {
                val plot = plots.at(world, x, y, z)?.takeIf { it !== Field.PATH } ?: plots.signAt(world, x, y, z)
                if (plot == null) texts.send(player, XPlotsMessages.notInPlot) else plugin.ui.admin(player, plot)
            }
            !left && sneaking -> {
                val sign = plots.signAt(world, x, y, z)
                if (sign != null) {
                    val turned = Facing.turn(sign.sign.facing)
                    plugin.builder.moveSign(sign, SignSpot(sign.sign.x, sign.sign.y, sign.sign.z, turned))
                    texts.send(player, XPlotsMessages.signTurned, "plot" to sign.id, "facing" to Facing.shown(turned))
                } else {
                    moveSign(player, world, x + faceX, y + faceY, z + faceZ)
                }
            }
            else -> {
                val which = if (left) 1 else 2
                val box = plugin.selections.corner(name, which, world, x, y, z).box
                val size = if (box == null) "" else texts.render(player, XPlotsMessages.cornerSize, "width" to box.width.toString(), "height" to box.height.toString(), "depth" to box.depth.toString())?.plain() ?: ""
                texts.send(player, XPlotsMessages.corner, "which" to which.toString(), "x" to x.toString(), "y" to y.toString(), "z" to z.toString(), "size" to size)
            }
        }
    }

    // Moves the nearest plot's sign to where the player pointed, facing them.
    private fun moveSign(player: PlayerRef, world: String, x: Int, y: Int, z: Int) {
        var nearest: Plot? = null
        var best = 16.0 * 16
        for (field in plugin.plots.fields()) {
            if (field.world != world) continue
            for (plot in field.plots()) {
                val b = plot.box
                val dx = maxOf(0, maxOf(b.minX - x, x - b.maxX)).toDouble()
                val dz = maxOf(0, maxOf(b.minZ - z, z - b.maxZ)).toDouble()
                val d = dx * dx + dz * dz
                if (d < best) {
                    best = d
                    nearest = plot
                }
            }
        }
        if (nearest == null) return texts.send(player, XPlotsMessages.noPlotNear)
        if (plugin.plots.signAt(world, x, y, z) != null || !plugin.server.isFree(world, x, y, z)) return texts.send(player, XPlotsMessages.inTheWay)
        val yaw = plugin.server.position(player)?.yaw ?: 0f
        plugin.builder.moveSign(nearest, SignSpot(x, y, z, Facing.toward(yaw)))
        texts.send(player, XPlotsMessages.signMoved, "plot" to nearest.id)
    }

    /** A free plot's, the owner's, or someone else's sign was clicked. On the player's thread. */
    fun signClicked(player: PlayerRef, name: String, plot: Plot) {
        if (!plugin.server.hasPermission(player, XPlotsPlugin.USE)) return texts.send(player, co.xenastudios.core.common.i18n.CoreMessages.commandNoPermission)
        val lease = plot.lease
        when {
            lease == null -> plugin.ui.rent(player, plot)
            lease.owner == player -> plugin.ui.plot(player, plot)
            else -> texts.send(player, XPlotsMessages.rentedBy, *plugin.rentals.placeholders(plot, player))
        }
    }

    private companion object {
        /** `x,y,z,yaw,pitch`, as a field's warp is stored (1.x's). */
        fun encode(at: Location): String = "${fixed(at.x, 2)},${fixed(at.y, 2)},${fixed(at.z, 2)},${fixed(at.yaw.toDouble(), 1)},${fixed(at.pitch.toDouble(), 1)}"

        fun decode(world: WorldRef, stored: String?): Location? {
            val parts = stored?.split(',') ?: return null
            if (parts.size != 5) return null
            val n = parts.map { it.trim().toDoubleOrNull() ?: return null }
            return Location(world, n[0], n[1], n[2], n[3].toFloat(), n[4].toFloat())
        }

        fun fixed(value: Double, places: Int): String {
            var scale = 1L
            repeat(places) { scale *= 10 }
            val rounded = kotlin.math.round(value * scale).toLong()
            val sign = if (rounded < 0) "-" else ""
            val abs = kotlin.math.abs(rounded)
            return "$sign${abs / scale}.${(abs % scale).toString().padStart(places, '0')}"
        }
    }
}
