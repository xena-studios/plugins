package co.xenastudios.xmarket.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.CommandTypes
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.ConfigGroup
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.ConfigSection
import co.xenastudios.core.common.config.ConfigType
import co.xenastudios.core.common.family.MessagesSection
import co.xenastudios.core.common.family.StorageSection
import co.xenastudios.core.common.update.UpdatesSection
import co.xenastudios.xmarket.api.Money
import co.xenastudios.xmarket.price.Currency

/**
 * xMarket's `config.yml`, version 1. The shop itself (its categories, items and prices) is in
 * `shops/`, one file per category.
 */
public object XMarketConfig : ConfigSchema(
    1,
    "xMarket: a server shop, with sell and worth.",
    "Docs: https://plugins.xenastudios.co/xmarket/configuration/",
    "",
    "Apply changes with /xmarket reload: everything applies, the shops/ files too, but",
    "storage. A bad value falls back to its default with a warning in the console. The",
    "categories, items and prices are in shops/, one file per category; the messages in lang/.",
) {
    /** The look and the language: the family's (`plugins/Xena/config.yml`) unless set here. */
    public val messages: MessagesSection = MessagesSection(this)

    public val commands: Commands = Commands(this)
    public val shop: Shop = Shop(this)
    public val sell: Sell = Sell(this)
    public val rules: Rules = Rules(this)

    /** Where history and limits are kept, read at startup. */
    public val storage: StorageSection = StorageSection(this, "xmarket_")

    public val updates: UpdatesSection = UpdatesSection(this)

    /** `commands:` */
    public class Commands(parent: ConfigGroup) : ConfigSection(
        parent, "commands",
        "Every command has the same settings:",
        "  enabled     false removes the command entirely, so another plugin can have its name",
        "  aliases     other names for it",
        "  permission  needed to use it; blank (\"\") lets anyone",
    ) {
        public val shop: CommandSettings = CommandSettings(this, "shop", "/shop [category]: the shop's menu, or one category's.", aliases = listOf("market"))
        public val sell: CommandSettings = CommandSettings(this, "sell", "/sell hand|all|<item> [amount]")
        public val sellgui: CommandSettings = CommandSettings(this, "sellgui", "/sellgui: a chest to drop items into; they're sold as it closes.")
        public val worth: CommandSettings = CommandSettings(this, "worth", "/worth [item]: what the item in your hand (or one named) sells for.")
    }

    /** `shop:` */
    public class Shop(parent: ConfigGroup) : ConfigSection(parent, "shop", "The shop's menus and prices.") {
        public val multiplier: ConfigKey<Money> = key(
            "multiplier", MONEY, Money.of(1),
            "Scales every price in shops/: 2 doubles them all, 0.5 halves them, for a server with",
            "bigger or smaller numbers. The files keep their own numbers.",
        )
        public val rows: ConfigKey<Int> = int("rows", 4, "Rows in /shop's menu of categories, 1 to 6.", min = 1, max = 6)
        public val title: ConfigKey<String> = string("title", "<dark_gray>Shop", "The title of /shop's menu. Markup.")
        public val currency: ConfigKey<Currency> = key(
            "currency", CURRENCY, Currency.Money,
            "What a category is paid in unless its file says: money (through xMoney, or any economy",
            "with Vault, or the Common Economy API on Fabric), xp (experience levels), or an item,",
            "such as item:diamond, which needs no economy at all.",
        )
        public val economy: ConfigKey<String> = key(
            "economy", ECONOMY, "auto",
            "Which economy money goes through: auto (xMoney if it's installed, otherwise Vault's or",
            "the Common Economy API's), xmoney, or vault.",
        )
        public val history: ConfigKey<Boolean> = boolean("history", true, "Keep each purchase and sale, for /shop history and /shop stats.")
    }

    /** `sell:` */
    public class Sell(parent: ConfigGroup) : ConfigSection(parent, "sell", "/sell, /sellgui and /worth.") {
        public val guiRows: ConfigKey<Int> = int("gui-rows", 6, "Rows in /sellgui's chest, 1 to 6.", min = 1, max = 6)
        public val renamed: ConfigKey<Boolean> = boolean(
            "renamed", true,
            "Whether an item a player only renamed sells as the plain item. Items with lore or a",
            "custom model (other plugins' items) never sell as a plain item: add them in game with",
            "/shop edit add to price them.",
        )
        public val confirmAll: ConfigKey<Boolean> = boolean("confirm-all", false, "Whether /sell all asks to be run again within 10 seconds before selling.")
    }

    /** `rules:` */
    public class Rules(parent: ConfigGroup) : ConfigSection(parent, "rules", "Discounts, sell boosts, limits and requirements.") {
        public val discounts: ConfigKey<Boolean> = boolean(
            "discounts", true,
            "Whether xmarket.discount.<n> takes n percent off what a player pays (the highest they",
            "have), and xmarket.sellboost.<n> adds n percent to what they're paid. n is 1 to 100.",
        )
        public val timezone: ConfigKey<Int> = int(
            "timezone", 0,
            "Hours ahead of UTC for when a day's and a week's limits start again (a week starts on",
            "Monday): 0 is UTC, -5 New York in winter, 1 Paris in winter.",
            min = -12, max = 14,
        )
        public val requirements: ConfigKey<Boolean> = boolean(
            "requirements", true,
            "Whether entries' requires: (placeholders, such as \"%player_level% >= 10\") are",
            "checked. They need PlaceholderAPI on Bukkit; without it an entry with requirements",
            "can't be used.",
        )
    }

    /** `multiplier`'s value as a percent: 1 is 100. */
    public fun multiplierPercent(config: Config): Long = (config[shop.multiplier].units / 100).coerceIn(1, 1_000_000)
}

/** A price or a multiplier: a number with up to four decimals, exact. */
public val MONEY: ConfigType<Money> = ConfigType.scalar("number", { it.toString() }) { raw ->
    val m = Money.parse(raw)
    if (m == null || m.isNegative || m.isZero) Outcome.Err("should be a number above 0, with up to four decimals") else Outcome.Ok(m)
}

private val CURRENCY: ConfigType<Currency> = ConfigType.scalar("currency", { co.xenastudios.xmarket.price.ShopFiles.currencyText(it) }) { raw ->
    Currency.of(raw)?.let { Outcome.Ok(it) } ?: Outcome.Err("should be money, xp or item:<id>")
}

private val ECONOMY: ConfigType<String> = ConfigType.scalar("economy", { it }) { raw ->
    val v = raw.trim().lowercase()
    if (v in setOf("auto", "xmoney", "vault")) Outcome.Ok(v) else Outcome.Err("should be auto, xmoney or vault")
}

/** A command's settings: whether it's on, its aliases and its permission. */
public class CommandSettings(
    parent: ConfigGroup,
    name: String,
    vararg comment: String,
    aliases: List<String> = emptyList(),
) : ConfigSection(parent, name, *comment) {
    public val command: String = name
    public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
    public val aliases: ConfigKey<List<String>> = list("aliases", CommandTypes.ALIAS, aliases)
    public val permission: ConfigKey<String> = key("permission", CommandTypes.PERMISSION, "xmarket.command.$name")
}
