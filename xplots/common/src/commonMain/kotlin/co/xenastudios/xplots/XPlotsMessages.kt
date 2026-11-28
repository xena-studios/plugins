package co.xenastudios.xplots

import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey

/**
 * Every message xPlots sends, in `lang/<language>.yml` (English here, in code). 1.x's messages
 * keep their English and their paths, less 1.x's `messages.` in front (`messages.rented` is
 * `rented`); its signs' lines and its menus' titles, names and lore are here too, under `signs.`
 * and `menus.` as in 1.x's config, a list's lines joined with `<newline>`. So
 * [co.xenastudios.xplots.config.Legacy] carries an owner's edits across.
 */
public object XPlotsMessages : MessageBundle() {
    private val PLOT = listOf("plot", "price", "period", "owner", "time", "slots", "max-slots", "size", "members")

    public val info: MessageKey = m(
        "info", "<prefix>xPlots <highlight><version></highlight> on <platform>: <highlight><fields></highlight> fields, <highlight><plots></highlight> plots, in <storage>. Rent: <rent>. <gray>/xplots wand | field | plot | reload | debug",
        "/xplots on its own, and /xplots info. <rent> is what rent is paid through: xMoney, Vault's economy, the item, or none yet.",
        placeholders = listOf("version", "platform", "fields", "plots", "storage", "rent"),
    )

    // ---- renting: 1.x's messages ----

    public val rented: MessageKey = m("rented", "<prefix><success>You rented</success> <highlight><plot></highlight> for <price>. It's yours for <time>.", "<plot>, <price>, <time>: what was rented, for what, and for how long.", placeholders = listOf("plot", "price", "time"), optional = PLOT)
    public val cantAfford: MessageKey = m("cant-afford", "<prefix><error>You need <highlight><price></highlight> for that.", "<price> is what it costs.", placeholders = listOf("price"))
    public val limitReached: MessageKey = m("limit-reached", "<prefix><error>You can't rent more than <highlight><max></highlight> at once.", "<max> is the player's limit.", placeholders = listOf("max"))
    public val taken: MessageKey = m("taken", "<prefix><error>Someone rented that plot first.")
    public val paid: MessageKey = m("paid", "<prefix><success>Paid</success> <highlight><price></highlight>. <highlight><plot></highlight> is yours for <time>.", "Rent paid. <price>, <plot>, <time> left now.", placeholders = listOf("price", "plot", "time"), optional = PLOT)
    public val prepaidFull: MessageKey = m("prepaid-full", "<prefix><error>Rent can be paid at most <highlight><max></highlight> ahead.", "<max> is max-prepaid.", placeholders = listOf("max"))
    public val abandoned: MessageKey = m("abandoned", "<prefix><highlight><plot></highlight> is no longer yours.", placeholders = listOf("plot"))
    public val refunded: MessageKey = m("refunded", "<prefix>You got back <highlight><price></highlight>.", "<price> is what came back.", placeholders = listOf("price"))
    public val slotBought: MessageKey = m("slot-bought", "<prefix><success>Bought a member slot</success> for <highlight><plot></highlight>. It has <slots> now.", "<slots> is how many it has now.", placeholders = listOf("plot", "slots"))
    public val slotsFull: MessageKey = m("slots-full", "<prefix><error><highlight><plot></highlight> has no free member slots. Buy one in its menu: <highlight>/plots</highlight>.", placeholders = listOf("plot"))
    public val maxSlots: MessageKey = m("max-slots", "<prefix><error><highlight><plot></highlight> has as many member slots as it can.", placeholders = listOf("plot"))
    public val memberAdded: MessageKey = m("member-added", "<prefix><success>Added</success> <highlight><player></highlight> to <plot>.", "<player> was added to <plot>.", placeholders = listOf("player", "plot"))
    public val memberRemoved: MessageKey = m("member-removed", "<prefix>Removed <highlight><player></highlight> from <plot>.", placeholders = listOf("player", "plot"))
    public val addedYou: MessageKey = m("added-you", "<prefix><highlight><owner></highlight> added you to <highlight><plot></highlight>. You can build there now.", "To the player added. <owner>, <plot>.", placeholders = listOf("owner", "plot"))
    public val removedYou: MessageKey = m("removed-you", "<prefix>You were removed from <highlight><plot></highlight>.", placeholders = listOf("plot"))
    public val alreadyMember: MessageKey = m("already-member", "<prefix><error><highlight><player></highlight> is already a member.", placeholders = listOf("player"))
    public val notMember: MessageKey = m("not-member", "<prefix><error><highlight><player></highlight> isn't a member.", placeholders = listOf("player"))
    public val notYourself: MessageKey = m("not-yourself", "<prefix><error>You own it, so you don't need a slot.")
    public val neverJoined: MessageKey = m("never-joined", "<prefix><error><highlight><player></highlight> has never played here.", "<player> has never joined this server.", placeholders = listOf("player"))
    public val noPlots: MessageKey = m("no-plots", "<prefix><error>You don't rent a plot. Click a free plot's sign to rent one.")
    public val whichPlot: MessageKey = m("which-plot", "<prefix><error>You have more than one plot: stand in the one you mean, or use <highlight>/plots</highlight>.", "Several plots and none chosen.")
    public val notYours: MessageKey = m("not-yours", "<prefix><error>That plot isn't yours.")
    public val rentedBy: MessageKey = m(
        "rented-by", "<prefix><highlight><plot></highlight> is rented by <highlight><owner></highlight> for <time>. Members: <members>.",
        "Clicking someone else's sign. <plot>, <owner>, <time>, and <members>: their names, or no-members.",
        placeholders = listOf("plot", "owner", "time", "members"), optional = PLOT,
    )
    public val noMembers: MessageKey = m("no-members", "none", "What <members> says for a plot with none.")
    public val expiresSoon: MessageKey = m(
        "expires-soon", "<prefix><highlight><plot></highlight>'s rent runs out in <highlight><time></highlight>. Pay more with <highlight>/plots</highlight>, or it's reset and anything in it is lost, chests included.",
        "<plot>'s rent runs out in <time>.", placeholders = listOf("plot", "time"),
    )
    public val expired: MessageKey = m("expired", "<prefix><highlight><plot></highlight>'s rent ran out, so it's free again.", "To the owner, if online.", placeholders = listOf("plot"))
    public val teleporting: MessageKey = m("teleporting", "<prefix>Teleporting in <highlight><seconds>s</highlight>. Don't move.", "<seconds> to wait.", placeholders = listOf("seconds"))
    public val teleportCancelled: MessageKey = m("teleport-cancelled", "<prefix><error>Teleport cancelled: you moved.")
    public val noWarp: MessageKey = m("no-warp", "<prefix><error><highlight><field></highlight> has no warp.", "/plots warp with no warp set. <field> is the field.", placeholders = listOf("field"))
    public val protectedHere: MessageKey = m("protected-here", "<error>You can't do that in this plot.", "On the action bar, when a player can't build or open something there.")
    public val unavailable: MessageKey = m("unavailable", "<prefix><error>That isn't available right now.", "Something xPlots needs isn't working, such as the economy. The console says what.")
    public val loading: MessageKey = m("loading", "<prefix><error>xPlots is still reading its plots. Try again in a moment.", "2.0: the database is read off the server thread as it starts.")

    // ---- 2.0: renewals, rollbacks ----

    public val renewed: MessageKey = m(
        "renewed", "<prefix><success>Renewed</success> <highlight><plot></highlight> for <price>. It's yours for <time>.",
        "Rent renewed on its own (auto-renew). To the owner if online, else when they next join.", placeholders = listOf("plot", "price", "time"),
    )
    public val renewFailed: MessageKey = m(
        "renew-failed", "<prefix><error>Couldn't renew <highlight><plot></highlight>: it needs <highlight><price></highlight>. Pay with <highlight>/plots</highlight> before it runs out.",
        "A renewal the owner's balance didn't cover.", placeholders = listOf("plot", "price"),
    )
    public val renewOn: MessageKey = m("renew-on", "<prefix><highlight><plot></highlight> now renews on its own, an hour before it runs out.", placeholders = listOf("plot"))
    public val renewOff: MessageKey = m("renew-off", "<prefix><highlight><plot></highlight> no longer renews on its own.", placeholders = listOf("plot"))
    public val rollbackDone: MessageKey = m(
        "rollback.done", "<prefix><success>Undid</success> <highlight><count></highlight> changes <highlight><player></highlight> made in <plot>.",
        "/plots rollback: xRewind rolled the player's changes back, in the plot only.", placeholders = listOf("count", "player", "plot"),
    )
    public val rollbackNone: MessageKey = m("rollback.none", "<prefix><highlight><player></highlight> changed nothing in <plot> in that time.", placeholders = listOf("player", "plot"))
    public val rollbackWorking: MessageKey = m("rollback.working", "<prefix><gray>Looking through xRewind's history...")
    public val rollbackUnavailable: MessageKey = m("rollback.unavailable", "<prefix><error>Rolling back in a plot needs xRewind 2.0, which isn't installed.")
    public val rollbackFailed: MessageKey = m("rollback.failed", "<prefix><error>xRewind couldn't roll that back: <reason>", "<reason> is xRewind's, in English.", placeholders = listOf("reason"))

    // ---- the signs: 1.x's signs.free, rented and expiring, four lines each ----

    private val SIGN = listOf("plot", "price", "period", "owner", "time", "slots", "max-slots", "size")
    public val signFree: MessageKey = m(
        "signs.free", "<dark_green><bold>FOR RENT<newline><plot><newline><price><newline><period>",
        "What plot signs say, four lines (split with <newline>). <plot> is the plot's name, <price> and",
        "<period> its rent, <owner> who rents it, <time> how long is left, <slots> and <max-slots> its",
        "member slots, <size> its size. A plot nobody rents.", optional = SIGN,
    )
    public val signRented: MessageKey = m("signs.rented", "<dark_red><bold>RENTED<newline><owner><newline><time> left<newline><slots>/<max-slots> members", optional = SIGN)
    public val signExpiring: MessageKey = m("signs.expiring", "<gold><bold>RENTED<newline><owner><newline><red><time> left<newline><slots>/<max-slots> members", "A rented plot whose rent runs out within signs.expiring-below.", optional = SIGN)

    // ---- the menus: 1.x's menus.*, each button's name and lore (lines split with <newline>) ----

    public val rentTitle: MessageKey = menu("rent.title", "Rent <plot>?", "Clicking a free plot's sign.")
    public val rentInfoName: MessageKey = menu("rent.info.name", "<highlight><plot>")
    public val rentInfoLore: MessageKey = menu("rent.info.lore", "<gray>Size: <white><size><newline><gray>Rent: <white><price></white> per <period><newline><gray>Member slots: <white><max-slots><newline><newline><gray>Anything you build is reset<newline><gray>when the rent runs out.")
    public val rentConfirmName: MessageKey = menu("rent.confirm.name", "<success>Rent for <price>")
    public val rentConfirmLore: MessageKey = menu("rent.confirm.lore", "<gray>Lasts <period>. Pay more any time<newline><gray>from /plots.")
    public val rentCancelName: MessageKey = menu("rent.cancel.name", "<error>Cancel")
    public val rentCancelLore: MessageKey = menu("rent.cancel.lore", "")

    public val plotTitle: MessageKey = menu("plot.title", "<plot>", "Clicking your own plot's sign, or /plots.")
    public val plotInfoName: MessageKey = menu("plot.info.name", "<highlight><plot>")
    public val plotInfoLore: MessageKey = menu("plot.info.lore", "<gray>Time left: <white><time><newline><gray>Members: <white><slots>/<max-slots><newline><gray>Size: <white><size>")
    public val payName: MessageKey = menu("plot.pay.name", "<success>Pay <periods>× rent", "<periods> is 1, 2 or 3; <price> what that costs; <time> how long it adds.", extra = listOf("periods"))
    public val payLore: MessageKey = menu("plot.pay.lore", "<gray>Costs <white><price><newline><gray>Adds <white><time>", extra = listOf("periods"))
    public val payFullName: MessageKey = menu("plot.pay-full.name", "<gray>Pay <periods>× rent", "Shown instead when paying would go past max-prepaid.", extra = listOf("periods"))
    public val payFullLore: MessageKey = menu("plot.pay-full.lore", "<gray>That's more than can be<newline><gray>paid ahead.", extra = listOf("periods"))
    public val membersName: MessageKey = menu("plot.members.name", "<highlight>Members")
    public val membersLore: MessageKey = menu("plot.members.lore", "<gray><slots> of <max-slots> slots used<newline><gray>Click to add or remove.")
    public val buySlotName: MessageKey = menu("plot.buy-slot.name", "<success>Buy a member slot")
    public val buySlotLore: MessageKey = menu("plot.buy-slot.lore", "<gray>Costs <white><price><newline><gray>Up to <max-slots>.")
    public val slotsFullName: MessageKey = menu("plot.slots-full.name", "<gray>Member slots full", "Shown instead when the plot has max-slots.")
    public val slotsFullLore: MessageKey = menu("plot.slots-full.lore", "<gray>This plot has as many<newline><gray>as it can.")
    public val homeName: MessageKey = menu("plot.home.name", "<highlight>Teleport here")
    public val homeLore: MessageKey = menu("plot.home.lore", "")
    public val abandonName: MessageKey = menu("plot.abandon.name", "<error>Abandon")
    public val abandonLore: MessageKey = menu("plot.abandon.lore", "<gray>Give the plot up. It's reset,<newline><gray>and what's in it is lost.")
    public val renewOnName: MessageKey = menu("plot.renew-on.name", "<success>Renews on its own", "2.0: with auto-renew, rent renewing from your balance. Click to turn it off.")
    public val renewOnLore: MessageKey = menu("plot.renew-on.lore", "<gray>An hour before it runs out,<newline><gray>one more <period> for <price>.<newline><gray>Click to turn it off.")
    public val renewOffName: MessageKey = menu("plot.renew-off.name", "<gray>Doesn't renew on its own")
    public val renewOffLore: MessageKey = menu("plot.renew-off.lore", "<gray>Click to renew it each <period><newline><gray>for <price>, from your balance.")

    public val listTitle: MessageKey = menu("list.title", "Your plots", "/plots with more than one plot.")
    public val listPlotName: MessageKey = menu("list.plot.name", "<highlight><plot>")
    public val listPlotLore: MessageKey = menu("list.plot.lore", "<gray>Time left: <white><time><newline><gray>Click to manage.")

    public val membersTitle: MessageKey = menu("members.title", "Members of <plot>")
    public val memberName: MessageKey = menu("members.member.name", "<highlight><player>", "<player> is the member.", extra = listOf("player"))
    public val memberLore: MessageKey = menu("members.member.lore", "<gray>Click to remove.", extra = listOf("player"))
    public val addName: MessageKey = menu("members.add.name", "<success>Add a member")
    public val addLore: MessageKey = menu("members.add.lore", "<gray><slots> of <max-slots> slots used")
    public val membersBackName: MessageKey = menu("members.back.name", "<gray>Back")
    public val membersBackLore: MessageKey = menu("members.back.lore", "")

    public val pickerTitle: MessageKey = menu("picker.title", "Add a member", "Adding a member: heads of the players online who can be seen (vanished players aren't).")
    public val pickerPlayerName: MessageKey = menu("picker.player.name", "<highlight><player>", extra = listOf("player"))
    public val pickerPlayerLore: MessageKey = menu("picker.player.lore", "<gray>Click to add.", extra = listOf("player"))
    public val previousName: MessageKey = menu("picker.previous.name", "<gray>Previous page")
    public val previousLore: MessageKey = menu("picker.previous.lore", "")
    public val nextName: MessageKey = menu("picker.next.name", "<gray>Next page")
    public val nextLore: MessageKey = menu("picker.next.lore", "")
    public val pickerBackName: MessageKey = menu("picker.back.name", "<gray>Back")
    public val pickerBackLore: MessageKey = menu("picker.back.lore", "")

    public val abandonTitle: MessageKey = menu("abandon.title", "Abandon <plot>?")
    public val abandonConfirmName: MessageKey = menu("abandon.confirm.name", "<error>Abandon <plot>")
    public val abandonConfirmLore: MessageKey = menu("abandon.confirm.lore", "<gray>It's reset, and anything<newline><gray>in it is lost.")
    public val abandonCancelName: MessageKey = menu("abandon.cancel.name", "<success>Keep it")
    public val abandonCancelLore: MessageKey = menu("abandon.cancel.lore", "")

    public val adminTitle: MessageKey = menu("admin.title", "<plot> (staff)", "Staff clicking a plot with the wand.")
    public val adminInfoName: MessageKey = menu("admin.info.name", "<highlight><plot>")
    public val adminInfoLore: MessageKey = menu("admin.info.lore", "<gray>Owner: <white><owner><newline><gray>Time left: <white><time><newline><gray>Members: <white><members>")
    public val adminHomeName: MessageKey = menu("admin.home.name", "<highlight>Teleport to the sign")
    public val adminHomeLore: MessageKey = menu("admin.home.lore", "")
    public val evictName: MessageKey = menu("admin.evict.name", "<error>Evict")
    public val evictLore: MessageKey = menu("admin.evict.lore", "<gray>End the rent now. The plot<newline><gray>is reset if its field says so.")
    public val resetName: MessageKey = menu("admin.reset.name", "<error>Reset")
    public val resetLore: MessageKey = menu("admin.reset.lore", "<gray>Put the plot back as it was<newline><gray>made. The renter keeps it.")

    // ---- staff: /xplots (English only in 1.x) ----

    public val wandGiven: MessageKey = m("staff.wand", "<prefix>Left click one corner of the plot area and right click the other, then <highlight>/xplots field create</highlight>.", "1.x's staff replies were English only.")
    public val wandName: MessageKey = m("staff.wand-name", "<gold>xPlots wand", "The wand's name and lore.")
    public val wandLore: MessageKey = m("staff.wand-lore", "<gray>Left click: first corner<newline><gray>Right click: second corner<newline><gray>Sneak + right click: move the nearest sign here<newline><gray>Sneak + left click: a plot's staff menu")
    public val corner: MessageKey = m("staff.corner", "<prefix>Corner <which> set at <highlight><x> <y> <z></highlight><size>.", "<size> is staff.corner-size once both are set.", placeholders = listOf("which", "x", "y", "z", "size"))
    public val cornerSize: MessageKey = m("staff.corner-size", " (<width> × <height> × <depth>)", placeholders = listOf("width", "height", "depth"))
    public val selected: MessageKey = m("staff.selected", "<prefix>Selected <highlight><width> × <height> × <depth></highlight> in <world>.", placeholders = listOf("width", "height", "depth", "world"))
    public val noSelection: MessageKey = m("staff.no-selection", "<prefix><error>Select the area first: <highlight>/xplots wand</highlight>, then click two corners, or <highlight>/xplots select</highlight> with WorldEdit's selection.")
    public val noWorld: MessageKey = m("staff.no-world", "<prefix><error>There's no world called <world>.", placeholders = listOf("world"))
    public val noWorldEdit: MessageKey = m("staff.no-worldedit", "<prefix><error>Type the corners (<highlight>/xplots select \\<world> \\<x1> \\<y1> \\<z1> \\<x2> \\<y2> \\<z2></highlight>), or make a WorldEdit selection first.", "/xplots select with no corners, and no WorldEdit selection to use.")
    public val selectionWorldGone: MessageKey = m("staff.selection-world-gone", "<prefix><error>The selection's world isn't loaded.")
    public val previewed: MessageKey = m(
        "staff.preview", "<prefix><highlight><cols> × <rows> = <count></highlight> plots of <size>, <path>-block paths<outlined>. <click:run_command:'/xplots confirm'><hover:show_text:'/xplots confirm'><success>[Confirm]</success></hover></click> <click:run_command:'/xplots cancel'><hover:show_text:'/xplots cancel'><error>[Cancel]</error></hover></click>",
        "A field's preview. <outlined> is staff.outlined for a player, nothing for the console.", placeholders = listOf("cols", "rows", "count", "size", "path", "outlined"),
    )
    public val outlined: MessageKey = m("staff.outlined", ", outlined around you")
    public val making: MessageKey = m("staff.making", "<prefix>Making <highlight><field></highlight>...", placeholders = listOf("field"))
    public val made: MessageKey = m("staff.made", "<prefix><success>Made</success> field <highlight><field></highlight>: <count> plots, each with its sign. Settings: <highlight>/xplots field info <field></highlight>.", placeholders = listOf("field", "count"))
    public val confirmNothing: MessageKey = m("staff.nothing-waiting", "<prefix><error>Nothing is waiting to be confirmed.")
    public val cancelled: MessageKey = m("staff.cancelled", "<prefix>Cancelled. Nothing changed.")
    public val deleteAsk: MessageKey = m(
        "staff.delete-ask", "<prefix>Delete <highlight><field></highlight> and its <count> plots<rented>? Signs come down; blocks stay. <click:run_command:'/xplots confirm'><hover:show_text:'/xplots confirm'><error>[Delete]</error></hover></click> <click:run_command:'/xplots cancel'><hover:show_text:'/xplots cancel'><success>[Keep]</success></hover></click>",
        "<rented> is staff.delete-rented when some are rented.", placeholders = listOf("field", "count", "rented"),
    )
    public val deleteRented: MessageKey = m("staff.delete-rented", ", <error><count> of them rented, with no refund</error>", placeholders = listOf("count"))
    public val deleted: MessageKey = m("staff.deleted", "<prefix>Deleted <highlight><field></highlight>. Its blocks are as they were.", placeholders = listOf("field"))
    public val noFields: MessageKey = m("staff.no-fields", "<prefix>No fields yet. Start with <highlight>/xplots wand</highlight>.")
    public val fieldsHeader: MessageKey = m("staff.fields", "<prefix>Fields:")
    public val fieldLine: MessageKey = m("staff.field-line", " <highlight><field></highlight> in <world>: <rented> of <count> rented", placeholders = listOf("field", "world", "rented", "count"))
    public val fieldInfo: MessageKey = m(
        "staff.field-info", "<prefix><highlight><field></highlight> in <world> at <x> <y> <z>: <count> plots of <size>, <rented> rented.",
        "Above a field's settings, one staff.setting each.", placeholders = listOf("field", "world", "x", "y", "z", "count", "size", "rented"),
    )
    public val settingLine: MessageKey = m("staff.setting", " <key>: <highlight><value></highlight><own>", "<own> is staff.own for the field's own.", placeholders = listOf("key", "value", "own"))
    public val own: MessageKey = m("staff.own", " (this field's own)")
    public val warpLine: MessageKey = m("staff.warp-line", " warp: <warp>", "<warp> is staff.warp-set or no-members.", placeholders = listOf("warp"))
    public val warpSetWord: MessageKey = m("staff.warp-set-word", "set")
    public val noField: MessageKey = m("staff.no-field", "<prefix><error>There's no field called <field>.", placeholders = listOf("field"))
    public val noPlot: MessageKey = m("staff.no-plot", "<prefix><error>There's no plot called <plot>. Plots are named like box-12.", placeholders = listOf("plot"))
    public val notASetting: MessageKey = m("staff.not-a-setting", "<prefix><error><key> isn't a setting. Use one of: <keys>.", placeholders = listOf("key", "keys"))
    public val badSetting: MessageKey = m("staff.bad-setting", "<prefix><error><key> <reason>.", "A value a setting can't take. <reason> says why, in English.", placeholders = listOf("key", "reason"))
    public val setDefault: MessageKey = m("staff.set-default", "<prefix><field> now uses the config's <highlight><key></highlight>.", placeholders = listOf("field", "key"))
    public val set: MessageKey = m("staff.set", "<prefix>Set <field>'s <highlight><key></highlight> to <highlight><value></highlight>.", placeholders = listOf("field", "key", "value"))
    public val signsMoved: MessageKey = m("staff.signs-moved", "<prefix>Moved <count> signs in <field> to <highlight><placement></highlight>.", placeholders = listOf("count", "field", "placement"))
    public val signsSame: MessageKey = m("staff.signs-same", "<prefix>Every sign in <field> is already <highlight><placement></highlight>.", placeholders = listOf("field", "placement"))
    public val warpRemoved: MessageKey = m("staff.warp-removed", "<prefix>Removed <field>'s warp.", placeholders = listOf("field"))
    public val warpSet: MessageKey = m("staff.warp-set", "<prefix>Set <field>'s warp here: <highlight>/plots warp <field></highlight>.", placeholders = listOf("field"))
    public val warpWorld: MessageKey = m("staff.warp-world", "<prefix><error>Stand in <world>, the field's world.", placeholders = listOf("world"))
    public val plotMade: MessageKey = m(
        "staff.plot-made", "<prefix><success>Made</success> <highlight><plot></highlight>. Its sign is outside its south edge, placed as the field's signs are; sneak and right click with the wand to move it.",
        placeholders = listOf("plot"),
    )
    public val plotDeleted: MessageKey = m("staff.plot-deleted", "<prefix>Deleted <highlight><plot></highlight>. Its blocks are as they were.", placeholders = listOf("plot"))
    public val plotInfo: MessageKey = m(
        "staff.plot-info", "<prefix><highlight><plot></highlight>, <size>, <from> to <to><rest>",
        "<rest> is staff.plot-free or staff.plot-rented, then staff.plot-resetting if it is.", placeholders = listOf("plot", "size", "from", "to", "rest"),
    )
    public val plotFree: MessageKey = m("staff.plot-free", ": free.")
    public val plotRented: MessageKey = m(
        "staff.plot-rented", "<newline> Owner: <highlight><owner></highlight>, <time> left<newline> Members (<slots>/<max-slots>): <members><newline> Renews on its own: <renews>",
        placeholders = listOf("owner", "time", "slots", "max-slots", "members", "renews"),
    )
    public val plotResetting: MessageKey = m("staff.plot-resetting", "<newline> Being put back now.")
    public val notRented: MessageKey = m("staff.not-rented", "<prefix><error><plot> isn't rented.", placeholders = listOf("plot"))
    public val evicted: MessageKey = m("staff.evicted", "<prefix>Evicted <owner> from <highlight><plot></highlight>.", placeholders = listOf("owner", "plot"))
    public val resetDone: MessageKey = m("staff.reset", "<prefix>Put <highlight><plot></highlight> back as it was made.", placeholders = listOf("plot"))
    public val resetting: MessageKey = m("staff.resetting", "<prefix>Putting <highlight><plot></highlight> back as it was made.", placeholders = listOf("plot"))
    public val ownerSet: MessageKey = m("staff.owner-set", "<prefix><highlight><plot></highlight> is <owner>'s, for <time>.", placeholders = listOf("plot", "owner", "time"))
    public val notInPlot: MessageKey = m("staff.not-in-plot", "<prefix><error>That isn't in a plot.")
    public val signTurned: MessageKey = m("staff.sign-turned", "<prefix>Turned <highlight><plot></highlight>'s sign to face <facing>.", placeholders = listOf("plot", "facing"))
    public val signMoved: MessageKey = m("staff.sign-moved", "<prefix>Moved <highlight><plot></highlight>'s sign here.", placeholders = listOf("plot"))
    public val noPlotNear: MessageKey = m("staff.no-plot-near", "<prefix><error>No plot within 16 blocks of there.")
    public val inTheWay: MessageKey = m("staff.in-the-way", "<prefix><error>There's something in the way there.")
    public val refused: MessageKey = m("staff.refused", "<prefix><error>Another plugin stopped that.", "A listener cancelled the change.")
    public val problem: MessageKey = m("staff.problem", "<prefix><error><reason>", "Why a field or plot can't go there: one of staff.why-*.", placeholders = listOf("reason"))
    public val whyName: MessageKey = m("staff.why-name", "Field names are up to 24 lowercase letters, digits and _.")
    public val whyTaken: MessageKey = m("staff.why-taken", "There's already a field called <field>.", placeholders = listOf("field"))
    public val whyTooSmall: MessageKey = m("staff.why-too-small", "Not even one plot of that size fits in the selection.")
    public val whyTooMany: MessageKey = m("staff.why-too-many", "<count> plots is more than the <max> a field can have. Check the plot size.", placeholders = listOf("count", "max"))
    public val whyOverlapsField: MessageKey = m("staff.why-overlaps-field", "That overlaps the field <field>.", placeholders = listOf("field"))
    public val whyOverlapsPlot: MessageKey = m("staff.why-overlaps-plot", "That overlaps the plot <plot>.", placeholders = listOf("plot"))
    public val whyOtherWorld: MessageKey = m("staff.why-other-world", "That's in another world from <field>.", placeholders = listOf("field"))
    public val whyTooWide: MessageKey = m("staff.why-too-wide", "A plot can be at most 256 blocks across.")
    public val badNumber: MessageKey = m("staff.bad-number", "<prefix><error><value> should be a whole number from <min> to <max>.", placeholders = listOf("value", "min", "max"))

    private fun menu(key: String, english: String, vararg comment: String, extra: List<String> = emptyList()): MessageKey =
        m("menus.$key", english, *comment, optional = PLOT + extra)

    // Declares what the English uses as its placeholders and the rest as optional, so an owner may add them.
    private fun m(key: String, english: String, vararg comment: String, placeholders: List<String> = emptyList(), optional: List<String> = emptyList()): MessageKey {
        val names = (placeholders + optional).distinct()
        val used = TAG.findAll(english).map { it.groupValues[1] }.toSet()
        return message(key, english, *comment, placeholders = names.filter { it in used }, optional = names.filter { it !in used })
    }

    private val TAG get() = Regex("<([a-z0-9_-]+)")

    /** Every message the lang files hold: the core's, then xPlots' own. */
    public val catalog: MessageCatalog = MessageCatalog.of(CoreMessages, this)
}
