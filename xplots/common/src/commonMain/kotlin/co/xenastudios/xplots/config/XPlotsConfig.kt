package co.xenastudios.xplots.config

import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.ConfigGroup
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.ConfigSection
import co.xenastudios.core.common.family.MessagesSection
import co.xenastudios.core.common.family.StorageSection
import co.xenastudios.core.common.update.UpdatesSection
import co.xenastudios.xplots.model.PlotSettings
import co.xenastudios.xplots.model.SignPlacement
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * xPlots' `config.yml`, version 2 (xPlots 2.0). Every setting 1.x had keeps its path and its
 * meaning: `plots`, `currency`, `max-plots`, `signs`' material and placement, `menus`' materials,
 * `teleport-warmup`, `reset-ms-per-tick` and `storage` are as they were, so a 1.x config carries
 * forward with only its texts moved (`messages`, the signs' lines and the menus' names and lore, to
 * `lang/`), its theme into `messages.theme` and `update-checker` into `updates` ([Legacy]). The
 * 1.x jar put back reads the rest as it was.
 */
public object XPlotsConfig : ConfigSchema(
    2,
    "xPlots: rentable plots, set up in a minute.",
    "Docs: https://plugins.xenastudios.co/xplots/configuration/",
    "",
    "Apply changes with /xplots reload: everything applies but storage. A bad value falls back to",
    "its default with a warning in the console; xPlots never stops over config. The messages, the",
    "signs' lines and the menus' text are in lang/.",
) {
    /** The look and the language: the family's (`plugins/Xena/config.yml`) unless set here. */
    public val messages: MessagesSection = MessagesSection(this)

    public val plots: Plots = Plots(this)
    public val currency: Currency = Currency(this)

    public val maxPlots: ConfigKey<Int> = int(
        "max-plots", 1,
        "How many plots a player may rent at once. A player with xplots.limit.<n> may rent <n>",
        "instead, the highest they have.",
        min = 0, max = 10_000,
    )

    public val signs: Signs = Signs(this)
    public val menus: Menus = Menus(this)

    public val teleportWarmup: ConfigKey<Int> = int(
        "teleport-warmup", 3,
        "Seconds to stand still before /plots home and /plots warp teleport. xplots.warmup.bypass",
        "skips it. With xTeleport installed, plot homes are also in /homes, with xTeleport's own warmup.",
        min = 0, max = 600,
    )

    public val resetMsPerTick: ConfigKey<Int> = int(
        "reset-ms-per-tick", 5,
        "How long putting a plot back may work each tick, in milliseconds. A tick is 50. A small",
        "plot takes a fraction of one.",
        min = 1, max = 40,
    )

    /** Where plots are kept: 1.x's `storage`. Read at startup: plots aren't copied across. */
    public val storage: StorageSection = StorageSection(this, "xplots_")

    /** The update checker: `updates.check` was 1.x's `update-checker.enabled`. */
    public val updates: UpdatesSection = UpdatesSection(this)

    init {
        // 2.0: texts go to lang/ (Legacy carries the edited ones there), the theme into messages.
        migrate(1, co.xenastudios.xplots.Startup.configOne)
    }

    /** `plots:` every field's settings, unless the field changes one with `/xplots field set`. */
    public class Plots(parent: ConfigGroup) : ConfigSection(
        parent, "plots",
        "Every field's settings, unless the field changes one with /xplots field set. Times are",
        "written like 3d, 12h or 1d12h.",
    ) {
        public val price: ConfigKey<Double> = double("price", 100.0, "What one period of rent costs.", min = 0.0)
        public val period: ConfigKey<kotlin.time.Duration> = duration("period", 3.days, "How long one payment of rent lasts.", min = 1.minutes)
        public val maxPrepaid: ConfigKey<kotlin.time.Duration> = duration(
            "max-prepaid", 7.days,
            "The most rent that can be paid ahead. A player can pay again while there's room, so a",
            "plot is never lost to a missed window.",
            min = 1.minutes,
        )
        public val startingSlots: ConfigKey<Int> = int("starting-slots", 1, "Members a new rental can add, besides the owner, who doesn't take a slot.", min = 0, max = 45)
        public val maxSlots: ConfigKey<Int> = int("max-slots", 3, "The most member slots a plot can have, after buying more.", min = 0, max = 45)
        public val slotPrice: ConfigKey<Double> = double("slot-price", 50.0, "What one more member slot costs.", min = 0.0)
        public val reset: ConfigKey<Boolean> = boolean(
            "reset", true,
            "Put a plot back as it was when it was made, once its rent runs out or it's abandoned.",
            "Anything built in it is lost, items in chests included.",
        )
        public val abandonRefund: ConfigKey<Int> = int("abandon-refund", 0, "How much of the unused rent abandoning a plot gives back, in percent.", min = 0, max = 100)
        public val autoRenew: ConfigKey<Boolean> = boolean(
            "auto-renew", false,
            "2.0: renew rent on its own for one more period as it runs out (an hour before), from",
            "the owner's balance, online or not, so a plot isn't lost while they're away. Needs an",
            "economy (currency.type: economy); an owner can turn it off for their plot in its menu.",
            "Each renewal is charged once, even across a crash or a restart.",
        )

        /** What the section says. */
        public fun read(config: Config): PlotSettings = PlotSettings(
            config[price], config[period], config[maxPrepaid], config[startingSlots], maxOf(config[startingSlots], config[maxSlots]),
            config[slotPrice], config[reset], config[abandonRefund], config[autoRenew],
        )
    }

    /** What rent is paid with. */
    public enum class CurrencyType { ECONOMY, VAULT, ITEM }

    /** `currency:` */
    public class Currency(parent: ConfigGroup) : ConfigSection(parent, "currency", "What rent is paid with.") {
        public val type: ConfigKey<CurrencyType> = enum(
            "type", CurrencyType.ECONOMY, CurrencyType.entries,
            "economy uses the server's economy: xMoney's, when it's installed (each payment in its",
            "ledger, with the plot), or any economy plugin's through Vault (EssentialsX, CMI). vault,",
            "1.x's name, is the same. item takes the item below from the player's inventory.",
        )
        public val item: Item = Item(this)
    }

    /** `currency.item:` */
    public class Item(parent: ConfigGroup) : ConfigSection(parent, "item", "For type: item.") {
        public val material: ConfigKey<String> = string("material", "gold_nugget")
        public val itemName: ConfigKey<String> = string(
            "name", "Coin",
            "The item's name as players see it, without colours, such as \"Coin\". Blank takes any",
            "item of the material.",
        )
        public val customModelData: ConfigKey<Int> = int("custom-model-data", 0, "The item's custom model data, for a resource pack's coin. 0 takes any.", min = 0)
        public val singular: ConfigKey<String> = string("singular", "Coin", "How amounts are written: \"1 Coin\", \"5 Coins\".")
        public val plural: ConfigKey<String> = string("plural", "Coins")
    }

    /** `signs:` the lines themselves are in lang/ (`signs.free`, `signs.rented`, `signs.expiring`). */
    public class Signs(parent: ConfigGroup) : ConfigSection(
        parent, "signs",
        "Plot signs. What they say is in lang/ (signs.free, signs.rented, signs.expiring), four",
        "lines each.",
    ) {
        public val expiringBelow: ConfigKey<kotlin.time.Duration> = duration("expiring-below", 1.days, "A rented plot's sign shows signs.expiring once its rent runs out within this.")
        public val material: ConfigKey<String> = string("material", "oak_sign", "The sign placed for each plot: any standing sign, such as dark_oak_sign. Before 1.14, sign.")
        public val position: ConfigKey<SignPlacement.Position> = enum(
            "position", SignPlacement.Position.LEFT_CORNER, SignPlacement.Position.entries,
            "Where on a plot's front edge its sign stands: middle, left-corner or right-corner, as",
            "seen from the walkway looking at the plot. A field can change it with",
            "/xplots field set <field> sign-position. Signs already placed move with",
            "/xplots field signs <field>.",
        )
        public val rotation: ConfigKey<SignPlacement.Rotation> = enum(
            "rotation", SignPlacement.Rotation.STRAIGHT, SignPlacement.Rotation.entries,
            "Which way a sign faces: straight at the walkway, or diagonal: turned 45° out from its",
            "corner, so it reads from both walkways where they cross. Any sign can also be turned",
            "by hand: sneak and right click it with the wand.",
        )

        public fun placement(config: Config): SignPlacement = SignPlacement(config[position], config[rotation])
    }

    /** A menu button's item: `menus.<menu>.<button>.material`. Its name and lore are in lang/. */
    public class Button(parent: ConfigGroup, name: String, default: String) : ConfigSection(parent, name) {
        public val material: ConfigKey<String> = string("material", default)
    }

    /** `menus:` the items; the titles, names and lore are in lang/ (`menus.*`). */
    public class Menus(parent: ConfigGroup) : ConfigSection(
        parent, "menus",
        "The plot menus' items, by their ids. Their titles, names and lore are in lang/ (menus.*).",
    ) {
        public val filler: ConfigKey<String> = string("filler", "gray_stained_glass_pane", "Fills the empty slots. air leaves them empty.")
        public val rent: Buttons = Buttons(this, "rent", "Clicking a free plot's sign.", "info", "oak_sign", "confirm", "lime_concrete", "cancel", "red_concrete")
        public val plot: Buttons = Buttons(
            this, "plot", "Clicking your own plot's sign, or /plots.",
            "info", "oak_sign", "pay", "clock", "pay-full", "gray_dye", "members", "player_head", "buy-slot", "emerald",
            "slots-full", "gray_dye", "home", "ender_pearl", "abandon", "barrier", "renew-on", "lime_dye", "renew-off", "gray_dye",
        )
        public val list: Buttons = Buttons(this, "list", "/plots with more than one plot.", "plot", "oak_sign")
        public val members: Buttons = Buttons(this, "members", "", "member", "player_head", "add", "lime_dye", "back", "arrow")
        public val picker: Buttons = Buttons(this, "picker", "Adding a member: heads of the players online.", "player", "player_head", "previous", "arrow", "next", "arrow", "back", "arrow")
        public val abandon: Buttons = Buttons(this, "abandon", "", "confirm", "red_concrete", "cancel", "lime_concrete")
        public val admin: Buttons = Buttons(this, "admin", "Staff clicking a plot with the wand.", "info", "oak_sign", "home", "ender_pearl", "evict", "iron_door", "reset", "tnt")
    }

    /** One menu's buttons, each `<button>.material` ([names] are a button's name, then its default item, in turn). One class for every menu. */
    public class Buttons(parent: ConfigGroup, name: String, comment: String, vararg names: String) : ConfigSection(parent, name, *(if (comment.isEmpty()) emptyArray() else arrayOf(comment))) {
        private val keys = HashMap<String, ConfigKey<String>>()

        init {
            var i = 0
            while (i < names.size) {
                keys[names[i]] = Button(this, names[i], names[i + 1]).material
                i += 2
            }
        }

        /** The item of the button called [button]. */
        public operator fun get(button: String): ConfigKey<String> = keys.getValue(button)
    }
}
