package co.xenastudios.xplots

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.menu.Click
import co.xenastudios.core.common.menu.Icon
import co.xenastudios.core.common.menu.Menu
import co.xenastudios.core.common.menu.MenuHandler
import co.xenastudios.core.common.menu.MenuView
import co.xenastudios.core.common.text.Component
import co.xenastudios.xplots.api.EndReason
import co.xenastudios.xplots.api.RentKind
import co.xenastudios.xplots.config.XPlotsConfig
import co.xenastudios.xplots.model.Plot

/**
 * The plot menus, 1.x's, on the core's chest menus (nothing in them is a real item, and every
 * click is cancelled by the platform). Each is drawn from the plot as it is when it opens, in the
 * viewer's language, and each button checks again when clicked, since someone else may have
 * changed the plot meanwhile. Made the first time a menu opens.
 */
public class Ui internal constructor(private val plugin: XPlotsPlugin) {
    private val menus get() = XPlotsConfig.menus

    private fun text(player: PlayerRef, key: MessageKey, placeholders: Array<out Pair<String, Any>>): Component =
        plugin.texts.render(player, key, *placeholders) ?: Component.EMPTY

    private fun icon(material: ConfigKey<String>, player: PlayerRef, name: MessageKey, lore: MessageKey, placeholders: Array<out Pair<String, Any>>): Icon? {
        val icon = plugin.server.icon(plugin.config[material]) ?: return null
        val loreText = plugin.texts.render(player, lore, *placeholders)
        val lines = if (loreText == null || loreText.plain().isEmpty()) emptyList() else splitLines(loreText)
        return icon.named(text(player, name, placeholders), lines)
    }

    private fun open(player: PlayerRef, title: MessageKey, rows: Int, icons: Array<Icon?>, handler: MenuHandler, placeholders: Array<out Pair<String, Any>>) {
        val filler = plugin.config[menus.filler]
        if (filler != "air" && filler != "minecraft:air") {
            val fill = plugin.server.icon(filler)?.named(Component.text(" "), emptyList())
            if (fill != null) for (i in icons.indices) if (icons[i] == null) icons[i] = fill
        }
        val name = splitLines(text(player, title, placeholders)).firstOrNull() ?: Component.EMPTY
        plugin.server.menus.open(player, Menu(name, rows, icons.toList(), handler))
    }

    private fun close(player: PlayerRef) {
        plugin.server.menus.viewing(player)?.close()
    }

    // ---- a free plot ----

    /** A free plot: its details, and rent or cancel. */
    public fun rent(player: PlayerRef, plot: Plot) {
        val p = plugin.rentals.placeholders(plot, player)
        val m = menus.rent
        val icons = arrayOfNulls<Icon>(27)
        icons[11] = icon(m["confirm"], player, XPlotsMessages.rentConfirmName, XPlotsMessages.rentConfirmLore, p)
        icons[13] = icon(m["info"], player, XPlotsMessages.rentInfoName, XPlotsMessages.rentInfoLore, p)
        icons[15] = icon(m["cancel"], player, XPlotsMessages.rentCancelName, XPlotsMessages.rentCancelLore, p)
        open(player, XPlotsMessages.rentTitle, 3, icons, Clicks(this, RENT, plot), p)
    }

    // ---- /plots ----

    /** `/plots`: the plot menu with one plot, a list with several. */
    public fun mine(player: PlayerRef) {
        val owned = plugin.plots.owned(player)
        when {
            owned.isEmpty() -> plugin.texts.send(player, XPlotsMessages.noPlots)
            owned.size == 1 -> plot(player, owned[0])
            else -> list(player, owned)
        }
    }

    private fun list(player: PlayerRef, owned: List<Plot>) {
        val shown = owned.take(54)
        val rows = (shown.size + 8) / 9
        val icons = arrayOfNulls<Icon>(rows * 9)
        for ((i, plot) in shown.withIndex()) {
            icons[i] = icon(menus.list["plot"], player, XPlotsMessages.listPlotName, XPlotsMessages.listPlotLore, plugin.rentals.placeholders(plot, player))
        }
        open(player, XPlotsMessages.listTitle, rows, icons, ListClicks(this, shown), emptyArray())
    }

    /** The owner's menu: pay rent, members, slots, renewing, teleport, abandon. */
    public fun plot(player: PlayerRef, plot: Plot) {
        if (!plugin.rentals.owns(player, plot)) {
            plugin.texts.send(player, XPlotsMessages.notYours)
            close(player)
            return
        }
        val m = menus.plot
        val s = plugin.rentals.settings(plot)
        val p = plugin.rentals.placeholders(plot, player)
        val icons = arrayOfNulls<Icon>(27)
        icons[4] = icon(m["info"], player, XPlotsMessages.plotInfoName, XPlotsMessages.plotInfoLore, p)
        for (periods in 1..3) {
            val pay = p + arrayOf(
                "periods" to periods.toString(),
                "price" to plugin.rentals.wallet.format(plugin.rentals.price(player, plot, RentKind.PAY, periods)),
                "time" to plugin.rentals.time(s.period.inWholeMilliseconds * periods),
            )
            icons[9 + periods] = if (plugin.rentals.canPay(plot, periods)) {
                icon(m["pay"], player, XPlotsMessages.payName, XPlotsMessages.payLore, pay)
            } else {
                icon(m["pay-full"], player, XPlotsMessages.payFullName, XPlotsMessages.payFullLore, pay)
            }
        }
        if (plugin.renewals.offered(plot)) {
            val renew = p + arrayOf("price" to plugin.rentals.wallet.format(plugin.rentals.price(player, plot, RentKind.RENEW, 1)))
            icons[13] = if (plugin.renewals.active(plot)) {
                icon(m["renew-on"], player, XPlotsMessages.renewOnName, XPlotsMessages.renewOnLore, renew)
            } else {
                icon(m["renew-off"], player, XPlotsMessages.renewOffName, XPlotsMessages.renewOffLore, renew)
            }
        }
        icons[14] = icon(m["members"], player, XPlotsMessages.membersName, XPlotsMessages.membersLore, p)
        val slot = p + arrayOf(
            "price" to plugin.rentals.wallet.format(plugin.rentals.price(player, plot, RentKind.SLOT, 0)),
            "max-slots" to s.maxSlots.toString(),
        )
        icons[15] = if ((plot.lease?.slots ?: 0) >= s.maxSlots) {
            icon(m["slots-full"], player, XPlotsMessages.slotsFullName, XPlotsMessages.slotsFullLore, slot)
        } else {
            icon(m["buy-slot"], player, XPlotsMessages.buySlotName, XPlotsMessages.buySlotLore, slot)
        }
        icons[16] = icon(m["home"], player, XPlotsMessages.homeName, XPlotsMessages.homeLore, p)
        icons[22] = icon(m["abandon"], player, XPlotsMessages.abandonName, XPlotsMessages.abandonLore, p)
        open(player, XPlotsMessages.plotTitle, 3, icons, Clicks(this, PLOT, plot), p)
    }

    private fun members(player: PlayerRef, plot: Plot) {
        if (!plugin.rentals.owns(player, plot)) return close(player)
        val m = menus.members
        val p = plugin.rentals.placeholders(plot, player)
        val icons = arrayOfNulls<Icon>(36)
        val members = plot.lease?.members?.entries?.take(27).orEmpty().map { it.key to it.value }
        for ((i, member) in members.withIndex()) {
            val made = icon(m["member"], player, XPlotsMessages.memberName, XPlotsMessages.memberLore, p + arrayOf("player" to member.second))
            icons[i] = head(made, member.first, member.second)
        }
        icons[30] = icon(m["add"], player, XPlotsMessages.addName, XPlotsMessages.addLore, p)
        icons[32] = icon(m["back"], player, XPlotsMessages.membersBackName, XPlotsMessages.membersBackLore, p)
        open(player, XPlotsMessages.membersTitle, 4, icons, MemberClicks(this, plot, members.map { it.first }), p)
    }

    /** Heads of the players online who can be seen and aren't the plot's yet, 45 to a page. */
    private fun picker(player: PlayerRef, plot: Plot, page: Int) {
        if (!plugin.rentals.owns(player, plot)) return close(player)
        val lease = plot.lease ?: return close(player)
        if (lease.members.size >= lease.slots) {
            plugin.texts.send(player, XPlotsMessages.slotsFull, "plot" to plot.id)
            return members(player, plot)
        }
        val candidates = plugin.server.online()
            .filter { it != player && !lease.trusts(it) && visible(player, it) }
            .mapNotNull { p -> plugin.server.name(p)?.let { p to it } }
            .sortedBy { it.second.lowercase() }
        val pages = maxOf(1, (candidates.size + 44) / 45)
        val current = page.coerceIn(0, pages - 1)
        val m = menus.picker
        val icons = arrayOfNulls<Icon>(54)
        val shown = candidates.drop(current * 45).take(45)
        for ((i, candidate) in shown.withIndex()) {
            val made = icon(m["player"], player, XPlotsMessages.pickerPlayerName, XPlotsMessages.pickerPlayerLore, arrayOf("player" to candidate.second))
            icons[i] = head(made, candidate.first, candidate.second)
        }
        if (current > 0) icons[45] = icon(m["previous"], player, XPlotsMessages.previousName, XPlotsMessages.previousLore, emptyArray())
        icons[49] = icon(m["back"], player, XPlotsMessages.pickerBackName, XPlotsMessages.pickerBackLore, emptyArray())
        if (current + 1 < pages) icons[53] = icon(m["next"], player, XPlotsMessages.nextName, XPlotsMessages.nextLore, emptyArray())
        open(player, XPlotsMessages.pickerTitle, 6, icons, PickerClicks(this, plot, shown, current), emptyArray())
    }

    /** Whether [viewer] may be shown [target]: the server's own visibility, and xVanish's. */
    internal fun visible(viewer: PlayerRef, target: PlayerRef): Boolean = plugin.server.canSee(viewer, target) && !plugin.hooks.vanished(viewer, target)

    private fun abandon(player: PlayerRef, plot: Plot) {
        val p = plugin.rentals.placeholders(plot, player)
        val m = menus.abandon
        val icons = arrayOfNulls<Icon>(27)
        icons[11] = icon(m["confirm"], player, XPlotsMessages.abandonConfirmName, XPlotsMessages.abandonConfirmLore, p)
        icons[15] = icon(m["cancel"], player, XPlotsMessages.abandonCancelName, XPlotsMessages.abandonCancelLore, p)
        open(player, XPlotsMessages.abandonTitle, 3, icons, Clicks(this, ABANDON, plot), p)
    }

    /** Staff's menu for any plot: its details, teleport, evict, reset. */
    public fun admin(player: PlayerRef, plot: Plot) {
        val p = plugin.rentals.placeholders(plot, player)
        val m = menus.admin
        val icons = arrayOfNulls<Icon>(27)
        icons[4] = icon(m["info"], player, XPlotsMessages.adminInfoName, XPlotsMessages.adminInfoLore, p)
        icons[11] = icon(m["home"], player, XPlotsMessages.adminHomeName, XPlotsMessages.adminHomeLore, p)
        icons[13] = icon(m["evict"], player, XPlotsMessages.evictName, XPlotsMessages.evictLore, p)
        icons[15] = icon(m["reset"], player, XPlotsMessages.resetName, XPlotsMessages.resetLore, p)
        open(player, XPlotsMessages.adminTitle, 3, icons, Clicks(this, ADMIN, plot), p)
    }

    /** Teleports the player to the plot's front, after the warmup. */
    public fun home(player: PlayerRef, plot: Plot) {
        if (!plugin.server.hasWorld(plot.field.world)) return
        plugin.teleports.teleport(player, front(plot))
    }

    private fun head(icon: Icon?, player: PlayerRef, name: String): Icon? {
        if (icon == null || !icon.item.endsWith("player_head")) return icon
        val data = plugin.server.head(player, name) ?: return icon
        return Icon(icon.item, icon.amount, icon.name, icon.lore, icon.glow, data)
    }

    // ---- clicks: one class per menu, told apart by kind ----

    private class Clicks(private val ui: Ui, private val kind: Int, private val plot: Plot) : MenuHandler {
        override fun click(viewer: PlayerRef, view: MenuView, slot: Int, click: Click) = ui.clicked(kind, plot, viewer, view, slot)
    }

    private fun clicked(kind: Int, plot: Plot, viewer: PlayerRef, view: MenuView, slot: Int) {
        when (kind) {
            RENT -> when (slot) {
                11 -> {
                    view.close()
                    plugin.rentals.rent(viewer, plugin.server.name(viewer) ?: viewer.toString(), plot)
                }
                15 -> view.close()
            }
            PLOT -> when (slot) {
                10, 11, 12 -> plugin.rentals.payRent(viewer, plot, slot - 9, Reopen(this, viewer, plot))
                13 -> if (plugin.renewals.offered(plot)) {
                    plugin.renewals.toggle(viewer, plot)
                    plot(viewer, plot)
                }
                14 -> members(viewer, plot)
                15 -> plugin.rentals.buySlot(viewer, plot, Reopen(this, viewer, plot))
                16 -> {
                    view.close()
                    home(viewer, plot)
                }
                22 -> abandon(viewer, plot)
            }
            ABANDON -> when (slot) {
                11 -> {
                    view.close()
                    plugin.rentals.abandon(viewer, plot)
                }
                15 -> plot(viewer, plot)
            }
            ADMIN -> when (slot) {
                11 -> {
                    view.close()
                    if (plugin.server.hasWorld(plot.field.world)) plugin.server.teleport(viewer, front(plot))
                }
                13 -> {
                    view.close()
                    val owner = plot.lease?.ownerName
                    if (owner != null && plugin.rentals.end(plot, EndReason.EVICTED)) {
                        plugin.texts.send(viewer, XPlotsMessages.evicted, "owner" to owner, "plot" to plot.id)
                    }
                }
                15 -> {
                    view.close()
                    plugin.resets.reset(plot, SignUpdate(plugin, plot))
                    plugin.texts.send(viewer, XPlotsMessages.resetting, "plot" to plot.id)
                }
            }
        }
    }

    /** After paying from the menu: the menu again, with the new time. */
    private class Reopen(private val ui: Ui, private val player: PlayerRef, private val plot: Plot) : () -> Unit {
        override fun invoke() {
            if (ui.plugin.server.menus.viewing(player) != null) ui.plugin.platform.scheduler.run(co.xenastudios.core.common.scheduler.Where.On(player.uuid), action = Again(ui, player, plot))
        }
    }

    private class Again(private val ui: Ui, private val player: PlayerRef, private val plot: Plot) : (co.xenastudios.core.common.scheduler.Task) -> Unit {
        override fun invoke(task: co.xenastudios.core.common.scheduler.Task) = ui.plot(player, plot)
    }

    private class ListClicks(private val ui: Ui, private val plots: List<Plot>) : MenuHandler {
        override fun click(viewer: PlayerRef, view: MenuView, slot: Int, click: Click) {
            plots.getOrNull(slot)?.let { ui.plot(viewer, it) }
        }
    }

    private class MemberClicks(private val ui: Ui, private val plot: Plot, private val members: List<PlayerRef>) : MenuHandler {
        override fun click(viewer: PlayerRef, view: MenuView, slot: Int, click: Click) {
            when {
                slot < members.size -> {
                    if (ui.plugin.rentals.owns(viewer, plot)) ui.plugin.rentals.removeMember(PlayerTeller(ui.plugin, viewer), viewer, plot, members[slot], staff = false)
                    ui.members(viewer, plot)
                }
                slot == 30 -> ui.picker(viewer, plot, 0)
                slot == 32 -> ui.plot(viewer, plot)
            }
        }
    }

    private class PickerClicks(private val ui: Ui, private val plot: Plot, private val shown: List<Pair<PlayerRef, String>>, private val page: Int) : MenuHandler {
        override fun click(viewer: PlayerRef, view: MenuView, slot: Int, click: Click) {
            when {
                slot < shown.size -> {
                    val (member, name) = shown[slot]
                    if (ui.plugin.rentals.owns(viewer, plot)) ui.plugin.rentals.addMember(PlayerTeller(ui.plugin, viewer), viewer, plot, member, name, staff = false)
                    ui.members(viewer, plot)
                }
                slot == 45 -> ui.picker(viewer, plot, page - 1)
                slot == 49 -> ui.members(viewer, plot)
                slot == 53 -> ui.picker(viewer, plot, page + 1)
            }
        }
    }

    private companion object {
        const val RENT = 0
        const val PLOT = 1
        const val ABANDON = 2
        const val ADMIN = 3
    }
}
