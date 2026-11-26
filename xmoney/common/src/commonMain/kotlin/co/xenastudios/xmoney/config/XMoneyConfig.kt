package co.xenastudios.xmoney.config

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.CommandTypes
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.ConfigContext
import co.xenastudios.core.common.config.ConfigGroup
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.ConfigSection
import co.xenastudios.core.common.config.ConfigType
import co.xenastudios.core.common.config.YamlNode
import co.xenastudios.core.common.family.MessagesSection
import co.xenastudios.core.common.family.StorageSection
import co.xenastudios.core.common.update.UpdatesSection
import co.xenastudios.xmoney.api.Amount
import co.xenastudios.xmoney.api.Rounding

/**
 * xMoney's `config.yml`, version 2 (xMoney 2.0). Every setting 1.x had keeps its path and its
 * meaning, so a 1.x config carries forward with nothing but its messages moved (to `lang/`), its
 * theme moved into `messages.theme` and `update-checker` into `updates`; the 1.x jar put back
 * reads the rest as it was.
 */
public object XMoneyConfig : ConfigSchema(
    2,
    "xMoney: the economy, and Vault, in one plugin.",
    "Docs: https://plugins.xenastudios.co/xmoney/configuration/",
    "",
    "Apply changes with /xmoney reload. A bad value falls back to its default with a",
    "warning in the console; xMoney never stops over config. The messages are in lang/.",
) {
    /** The look and the language: the family's (`plugins/Xena/config.yml`) unless set here. */
    public val messages: MessagesSection = MessagesSection(this)

    public val currency: CurrencySection = CurrencySection(this)

    public val startingBalance: ConfigKey<Amount> = key(
        "starting-balance", AmountType.AMOUNT, Amount.ZERO,
        "What a player has the first time they join. Players who already have an account keep",
        "theirs.",
    )

    public val maxBalance: ConfigKey<Amount> = key(
        "max-balance", AmountType.AMOUNT, Amount.of(1_000_000_000_000L),
        "The most one account can hold. Payments that would take a balance over it are refused.",
        "At most 100,000,000,000,000.",
        check = AmountType.MORE_THAN_ZERO,
    )

    public val commands: Commands = Commands(this)
    public val ledger: LedgerSection = LedgerSection(this)

    /** Where balances and the ledger are kept: 1.x's `storage`, read at startup. */
    public val storage: StorageSection = StorageSection(this, "xmoney_")

    public val network: Network = Network(this)

    public val networkWaitMs: ConfigKey<Int> = int(
        "network-wait-ms", 50,
        "On a network: the longest, in milliseconds, a withdrawal made on a server thread (a",
        "shop's Vault call) waits for the shared database to take it up. If it hasn't, it's",
        "refused and nothing is taken. At most 250, so a slow database can't stall the server.",
        "Commands and xMoney's API don't hold a server thread, so they wait up to 5 seconds.",
        min = 1, max = 250,
    )

    /** The update checker: `updates.check` was 1.x's `update-checker.enabled`. */
    public val updates: UpdatesSection = UpdatesSection(this)

    init {
        // 2.0: messages go to lang/ (Legacy carries the edited ones there), the theme into messages.
        migrate(1) { Legacy.toTwo(this) }
    }

    /** `currency:` how money looks. */
    public class CurrencySection(parent: ConfigGroup) : ConfigSection(
        parent, "currency",
        "How money looks. Other plugins show it this way too, through Vault's format.",
    ) {
        public val singular: ConfigKey<String> = string("singular", "dollar", "The currency's name for one, and for more than one.")
        public val plural: ConfigKey<String> = string("plural", "dollars")
        public val symbol: ConfigKey<String> = string("symbol", "$")
        public val decimals: ConfigKey<Int> = int(
            "decimals", 2,
            "Decimal places: 2 for cents, 0 for whole coins. At most 4. Amounts are shown with",
            "these, and amounts typed into commands may have at most this many. Other plugins'",
            "amounts keep 4 places, so a payment split in two costs what it would whole.",
            "Lowering it later keeps the parts of balances it no longer shows.",
            min = 0, max = 4,
        )
        public val format: ConfigKey<String> = string(
            "format", "{symbol}{amount}",
            "How an amount is written. {amount} is the number, {symbol} the symbol, and {name}",
            "the singular or plural name: \"{amount} {name}\" gives \"12.50 dollars\".",
        )
        public val thousandsSeparator: ConfigKey<String> = string("thousands-separator", ",", "Put between thousands, as in 1,000,000. Blank for none.")
        public val decimalSeparator: ConfigKey<String> = string("decimal-separator", ".")
        public val shortSuffixes: ConfigKey<List<String>> = key(
            "short-suffixes", ConfigType.listOf(ConfigType.STRING), listOf("k", "M", "B", "T"),
            "The short form's thousands, millions, billions and trillions, as in 1.2k.",
            check = AmountType.FOUR,
        )
    }

    /** `commands:` */
    public class Commands(parent: ConfigGroup) : ConfigSection(
        parent, "commands",
        "Each command can be turned off (enabled: false, so another plugin can have its name),",
        "given other aliases, or need another permission. A blank permission lets everyone use it.",
    ) {
        public val balance: Balance = Balance(this)
        public val pay: Pay = Pay(this)
        public val baltop: Baltop = Baltop(this)
        public val eco: CommandSettings = CommandSettings(
            this, "eco", listOf("economy"), "xmoney.eco",
            "/eco give|take|set|reset|history: staff commands. Each part has its own permission:",
            "xmoney.eco.give, .take, .set, .reset and .history.",
        )
    }

    /** `commands.balance:` */
    public class Balance(parent: ConfigGroup) : CommandSettings(
        parent, "balance", listOf("bal", "money"), "",
        "/balance [player]: a player's balance. Someone else's needs permission-others.",
    ) {
        public val permissionOthers: ConfigKey<String> = key("permission-others", CommandTypes.PERMISSION, "xmoney.balance.others")
    }

    /** `commands.pay:` */
    public class Pay(parent: ConfigGroup) : CommandSettings(
        parent, "pay", emptyList(), "",
        "/pay <player> <amount>: send money to another player, online or not.",
    ) {
        public val minimum: ConfigKey<Amount> = key("minimum", AmountType.AMOUNT, Amount.parse("0.01")!!, "The least one payment can be.")
        public val confirmAbove: ConfigKey<Amount> = key(
            "confirm-above", AmountType.AMOUNT, Amount.ZERO,
            "Payments of this much or more ask the player to confirm first. 0 never asks.",
        )
    }

    /** `commands.baltop:` */
    public class Baltop(parent: ConfigGroup) : CommandSettings(
        parent, "baltop", listOf("balancetop", "moneytop"), "",
        "/baltop [page]: the richest players.",
    ) {
        public val perPage: ConfigKey<Int> = int("per-page", 10, min = 1, max = 50)
    }

    /** `ledger:` */
    public class LedgerSection(parent: ConfigGroup) : ConfigSection(
        parent, "ledger", "The ledger: every change to every balance, for /eco history.",
    ) {
        public val keepDays: ConfigKey<Int> = int(
            "keep-days", 365,
            "Delete ledger rows older than this many days, checked at startup and once a day. 0",
            "keeps them all. Balances never depend on old rows.",
            min = 0, max = 100_000,
        )
    }

    /**
     * `network:` 1.x's, with `check-every` in whole seconds as 1.x wrote it (the core's network
     * section reads a duration, `3s`, which the 1.x jar put back couldn't read).
     */
    public class Network(parent: ConfigGroup) : ConfigSection(
        parent, "network",
        "For several servers sharing balances through one MySQL or MariaDB database. Every",
        "server must use the same storage settings and table-prefix. Read at startup only.",
        "Experimental: a withdrawal is decided by the shared database, so a shop's Vault call",
        "waits for it on the server thread (see network-wait-ms), and while the database is",
        "away, withdrawals are refused. Money is never lost or made either way.",
    ) {
        public val enabled: ConfigKey<Boolean> = boolean(
            "enabled", false,
            "On when several servers share this plugin's MySQL or MariaDB database. Leave it off",
            "for a single server: it does nothing on SQLite.",
        )
        public val checkEvery: ConfigKey<Int> = int("check-every", 3, "Seconds between checks for the other servers' changes.", min = 1, max = 60)
    }

    /** What the config says about the currency, worked out once per config. */
    public fun currency(config: Config): CurrencySettings {
        val c = currency
        return CurrencySettings(
            config[c.singular], config[c.plural], config[c.symbol], config[c.decimals], config[c.format],
            config[c.thousandsSeparator], config[c.decimalSeparator], config[c.shortSuffixes],
        )
    }

}

/**
 * A command's settings: whether it's on, its aliases and its permission, at 1.x's paths
 * (`commands.<name>.*`). [command] is its name, which can't change.
 */
public open class CommandSettings(
    parent: ConfigGroup,
    name: String,
    defaultAliases: List<String>,
    permission: String,
    vararg comment: String,
) : ConfigSection(parent, name, *comment) {
    /** The command's name. */
    public val command: String = name

    public val enabled: ConfigKey<Boolean> = boolean("enabled", true)
    public val aliases: ConfigKey<List<String>> = list("aliases", CommandTypes.ALIAS, defaultAliases)
    public val permission: ConfigKey<String> = key("permission", CommandTypes.PERMISSION, permission)
}

/** The `currency` section, read: how amounts are written and typed. */
public class CurrencySettings(
    public val singular: String,
    public val plural: String,
    public val symbol: String,
    public val decimals: Int,
    public val format: String,
    public val thousandsSeparator: String,
    public val decimalSeparator: String,
    public val shortSuffixes: List<String>,
)

/** The config's amount type, apart from the schema so it exists before the schema is built. */
public object AmountType {
    // The config's two checks as one class, told apart by kind, not two lambdas (each a class of its own).
    private class Check(private val kind: Int) : (Any) -> String? {
        override fun invoke(value: Any): String? = when (kind) {
            0 -> if ((value as Amount).isZero) "must be more than 0" else null
            else -> if ((value as List<*>).size == 4) null else "must be four suffixes"
        }
    }

    @Suppress("UNCHECKED_CAST")
    internal val MORE_THAN_ZERO: (Amount) -> String? = Check(0) as (Amount) -> String?

    @Suppress("UNCHECKED_CAST")
    internal val FOUR: (List<String>) -> String? = Check(1) as (List<String>) -> String?

    /**
     * An amount in the config: a plain number, rounded half up to four places, zero to
     * [Amount.MAX_WHOLE]. Written as 1.x wrote it, a bare number.
     */
    public val AMOUNT: ConfigType<Amount> = object : ConfigType<Amount>() {
        override val name: String get() = "number"
        override val plain: Boolean get() = true

        override fun decode(node: YamlNode, context: ConfigContext): Outcome<Amount, String> {
            if (node !is YamlNode.Scalar) return Outcome.Err("should be a single value")
            val amount = Amount.parse(node.content.trim().replace("_", ""), Rounding.HALF_UP)
                ?: return Outcome.Err("should be an amount from 0 to 100,000,000,000,000")
            return if (amount.isNegative) Outcome.Err("must be at least 0") else Outcome.Ok(amount)
        }

        override fun encode(value: Amount): YamlNode = YamlNode.Scalar(value.toString())
    }
}
