package co.xenastudios.xmoney.config;

import co.xenastudios.core.ErrorMessages;
import co.xenastudios.core.UpdateCheckerSettings;
import co.xenastudios.core.XenaConfig;
import co.xenastudios.core.command.Toggle;
import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.config.Range;
import co.xenastudios.core.db.NetworkSettings;
import co.xenastudios.core.db.StorageSettings;
import co.xenastudios.core.text.ChatPages;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.TextStyle;
import co.xenastudios.core.text.Theme;
import co.xenastudios.xmoney.XMoney;
import java.util.List;

/** xMoney's config.yml. Its defaults are {@link #DEFAULTS}; the docs show the file they produce. */
public record XMConfig(
    @Comment({
        "The look xMoney's messages share. The default messages use these as tags, so changing",
        "a colour here restyles them all. Write a style as MiniMessage tags: <gray>,",
        "<#F0D35A>, or <gold><bold>."})
    Theme theme,
    @Comment("What players and staff are told.") Messages messages,
    @Comment({
        "How money looks. Other plugins show it this way too, through Vault's format. Changes",
        "apply on /xmoney reload."})
    CurrencySettings currency,
    @Comment({
        "What a player has the first time they join. Players who already have an account keep",
        "theirs."})
    @Range(min = 0, max = 100_000_000_000_000L) double startingBalance,
    @Comment({
        "The most one account can hold. Payments that would take a balance over it are refused.",
        "At most 100,000,000,000,000."})
    @Range(min = 1, max = 100_000_000_000_000L) double maxBalance,
    @Comment({
        "Each command can be turned off (enabled: false, so another plugin can have its name),",
        "given other aliases, or need another permission. A blank permission lets everyone use it."})
    Commands commands,
    @Comment("The ledger: every change to every balance, for /eco history.") LedgerSettings ledger,
    @Comment({
        "Where balances and the ledger are kept. sqlite needs nothing set up. Changes apply on a",
        "restart; balances aren't copied from one to the other."})
    StorageSettings storage,
    @Comment({
        "For several servers sharing balances through one MySQL or MariaDB database. Every",
        "server must use the same storage settings and table-prefix. Applies on a restart."})
    NetworkSettings network,
    @Comment("Tells you when a new version is out.") UpdateCheckerSettings updateChecker) implements XenaConfig {

    @Override
    public ErrorMessages errorMessages() {
        return messages.error();
    }

    public record CurrencySettings(
        @Comment("The currency's name for one, and for more than one.") String singular,
        String plural,
        String symbol,
        @Comment({
            "Decimal places: 2 for cents, 0 for whole coins. At most 4. Amounts are rounded to",
            "these. Lowering it later keeps the parts of balances it no longer shows."})
        @Range(min = 0, max = 4) int decimals,
        @Comment({
            "How an amount is written. {amount} is the number, {symbol} the symbol, and {name}",
            "the singular or plural name: \"{amount} {name}\" gives \"12.50 dollars\"."})
        String format,
        @Comment("Put between thousands, as in 1,000,000. Blank for none.") String thousandsSeparator,
        String decimalSeparator,
        @Comment("The short form's thousands, millions, billions and trillions, as in 1.2k.")
        List<String> shortSuffixes) {}

    public record Commands(
        @Comment("/balance [player]: a player's balance. Someone else's needs permission-others.") Balance balance,
        @Comment("/pay <player> <amount>: send money to another player, online or not.") Pay pay,
        @Comment("/baltop [page]: the richest players.") Baltop baltop,
        @Comment({
            "/eco give|take|set|reset|history: staff commands. Each part has its own permission:",
            "xmoney.eco.give, .take, .set, .reset and .history."})
        Eco eco) {}

    public record Balance(boolean enabled, List<String> aliases, String permission, String permissionOthers) implements Toggle {}

    public record Pay(
        boolean enabled, List<String> aliases, String permission,
        @Comment("The least one payment can be.") @Range(min = 0, max = 100_000_000_000_000L) double minimum,
        @Comment("Payments of this much or more ask the player to confirm first. 0 never asks.")
        @Range(min = 0, max = 100_000_000_000_000L) double confirmAbove) implements Toggle {}

    public record Baltop(
        boolean enabled, List<String> aliases, String permission,
        @Range(min = 1, max = 50) int perPage) implements Toggle {}

    public record Eco(boolean enabled, List<String> aliases, String permission) implements Toggle {}

    public record LedgerSettings(
        @Comment({
            "Delete ledger rows older than this many days, checked at startup and once a day. 0",
            "keeps them all. Balances never depend on old rows."})
        @Range(min = 0, max = 100_000) int keepDays) {}

    public record Messages(
        ErrorMessages error,
        @Comment("/balance.") BalanceMessages balance,
        @Comment("/pay.") PayMessages pay,
        @Comment("/baltop.") BaltopMessages baltop,
        @Comment("/eco and /eco history.") EcoMessages eco,
        @Comment("/xmoney import.") ImportMessages imports,
        @Comment("Amounts typed into commands.") AmountMessages amount) {}

    public record BalanceMessages(
        @Comment("Your own. <balance> is formatted, as is every amount in messages.") Message own,
        @Comment("Someone else's. <player> is their name.") Message other,
        @Comment("Nobody by that name has played here. <player> is the name typed.") Message unknown) {}

    public record PayMessages(
        @Comment("To the payer. <amount>, <player>, and <balance>: what they have left.") Message sent,
        @Comment("To whoever is paid, if they're online. <amount>, <player>: who paid.") Message received,
        Message self,
        @Comment("Below the minimum. <minimum>.") Message tooLittle,
        @Comment("The payer hasn't got it. <amount>, <balance>.") Message notEnough,
        @Comment({
            "A payment of confirm-above or more asks first. <command> runs it, confirmed; put it",
            "in a click."})
        Message confirm,
        @Comment("It would take them over max-balance. <player>.") Message limit,
        @Comment("Another plugin refused it.") Message cancelled,
        @Comment("On a network, the shared database didn't answer in time. Nothing was sent.") Message unavailable) {}

    public record BaltopMessages(
        @Comment("Above the list. <total> is every player's money added up, <count> how many players.") Message header,
        @Comment("One player. <rank>, <player>, <balance>.") Message line,
        Message empty,
        @Comment("Below a page, when there's more than one. <page>, <pages>, and <next>/<previous>: the commands.")
        Message footer) {}

    public record EcoMessages(
        @Comment("<amount>, <player>, and <balance>: what they have now.") Message given,
        Message taken,
        Message set,
        Message reset,
        @Comment("/eco take for more than they have. <player>, <balance>.") Message notEnough,
        @Comment("It would take them over max-balance. <player>.") Message limit,
        @Comment("Another plugin refused it.") Message cancelled,
        @Comment("On a network, the shared database didn't answer in time. Nothing changed.") Message unavailable,
        @Comment("Sent while the history is read from the database.") Message working,
        @Comment("Above a page of history. <player>, <count>: how many changes in all.") Message historyHeader,
        @Comment({
            "One change. <ago>: how long ago, <change>: + or - the amount, <what>: what it was,",
            "<balance>: the balance after."})
        Message historyLine,
        @Comment("The player has no history.") Message historyEmpty,
        Message historyFooter,
        @Comment("How <change> looks when money came in, and when it went out. <amount>.") Message changeIn,
        Message changeOut,
        @Comment("The words <what> shows for each kind of change. <other>: the other player, <source>: who did it, <reason>.")
        Words words) {}

    public record Words(
        String start, String paidTo, String paidBy, String given, String taken, String set,
        @Comment("A plugin's change, without and with a reason.") String plugin, String pluginReason,
        String imported) {}

    public record ImportMessages(
        @Comment({
            "A dry run: what an import would do, changing nothing. <source>, <count> accounts,",
            "<total>: their money added up, <skipped> that can't be imported, <command>: the import, confirmed."})
        Message dryRun,
        @Comment("<count> balances set from <source>, <skipped> skipped.") Message done,
        @Comment("There's nothing to import. <source>, <reason>.") Message nothing,
        @Comment("The other plugin's data couldn't be read. <source>, <reason>.") Message failed,
        Message running,
        Message reading) {}

    public record AmountMessages(
        @Comment("Not a number. <amount> is what was typed.") Message invalid,
        @Comment("More decimals than the currency has. <decimals>.") Message tooPrecise,
        @Comment("Past the most an account can hold.") Message tooLarge) {}

    public static final String ECO = XMoney.ID + ".eco";

    public static final XMConfig DEFAULTS = new XMConfig(
        new Theme(
            "<#F0D35A>»</#F0D35A> ",
            TextStyle.parse("<gray>"),
            TextStyle.parse("<#F0D35A>"),
            TextStyle.parse("<#5EE08A>"),
            TextStyle.parse("<#FF6B6B>")),
        new Messages(
            ErrorMessages.DEFAULTS,
            new BalanceMessages(
                Message.of("<prefix>Balance: <highlight><balance>"),
                Message.of("<prefix><highlight><player></highlight>'s balance: <highlight><balance>"),
                Message.of("<prefix><error><highlight><player></highlight> has never played here.")),
            new PayMessages(
                Message.of("<prefix><success>Sent</success> <highlight><amount></highlight> to <highlight><player></highlight>. You have <balance> left."),
                Message.of("<prefix><success>Received</success> <highlight><amount></highlight> from <highlight><player></highlight>."),
                Message.of("<prefix><error>You can't pay yourself."),
                Message.of("<prefix><error>The least you can send is <highlight><minimum></highlight>."),
                Message.of("<prefix><error>You don't have <highlight><amount></highlight>. You have <balance>."),
                Message.of("<prefix>Send <highlight><amount></highlight> to <highlight><player></highlight>? "
                    + "<click:run_command:'<command>'><hover:show_text:'<command>'><success>[Confirm]</success></hover></click>"),
                Message.of("<prefix><error><highlight><player></highlight> can't hold that much."),
                Message.of("<prefix><error>That payment was refused."),
                Message.of("<prefix><error>The bank can't be reached right now. Nothing was sent; try again in a moment.")),
            new BaltopMessages(
                Message.of("<prefix>Richest players. <gray>All <count>: <total>"),
                Message.of("<gray>#<rank></gray> <highlight><player></highlight> <balance>"),
                Message.of("<prefix>Nobody has any money yet."),
                Message.of(ChatPages.FOOTER)),
            new EcoMessages(
                Message.of("<prefix><success>Gave</success> <highlight><player></highlight> <amount>. They have <balance>."),
                Message.of("<prefix><success>Took</success> <amount> from <highlight><player></highlight>. They have <balance>."),
                Message.of("<prefix><success>Set</success> <highlight><player></highlight>'s balance to <balance>."),
                Message.of("<prefix><success>Reset</success> <highlight><player></highlight>'s balance to <balance>."),
                Message.of("<prefix><error><highlight><player></highlight> only has <balance>."),
                Message.of("<prefix><error><highlight><player></highlight> can't hold that much."),
                Message.of("<prefix><error>Another plugin refused that change."),
                Message.of("<prefix><error>The shared database can't be reached right now. Nothing changed."),
                Message.of("<prefix><gray>Looking..."),
                Message.of("<prefix>History of <highlight><player></highlight>: <count> changes"),
                Message.of("<gray><ago> ago</gray> <change> <what> <gray>→ <balance>"),
                Message.of("<prefix><highlight><player></highlight> has no history."),
                Message.of(ChatPages.FOOTER),
                Message.of("<success>+<amount></success>"),
                Message.of("<error>-<amount></error>"),
                new Words("starting balance", "paid <other>", "from <other>", "given by <source>", "taken by <source>",
                    "set by <source>", "<source>", "<source>: <reason>", "imported from <source>")),
            new ImportMessages(
                Message.of("<prefix><highlight><source></highlight> has <highlight><count></highlight> balances, <total> in all"
                    + " (<skipped> can't be imported). Importing sets each of those players' balance here to theirs. "
                    + "<click:run_command:'<command>'><hover:show_text:'<command>'><success>[Import]</success></hover></click>"),
                Message.of("<prefix><success>Imported</success> <highlight><count></highlight> balances from <source>. <skipped> skipped."),
                Message.of("<prefix><error>Nothing to import from <source>: <reason>"),
                Message.of("<prefix><error>Couldn't read <source>: <reason>"),
                Message.of("<prefix><error>An import is already running."),
                Message.of("<prefix><gray>Reading <source>...")),
            new AmountMessages(
                Message.of("<prefix><error><highlight><amount></highlight> isn't an amount. Try 250, 12.5 or 1.5k."),
                Message.of("<prefix><error>Amounts have at most <highlight><decimals></highlight> decimals."),
                Message.of("<prefix><error>That's more than an account can hold."))),
        new CurrencySettings("dollar", "dollars", "$", 2, "{symbol}{amount}", ",", ".", List.of("k", "M", "B", "T")),
        0,
        1_000_000_000_000d,
        new Commands(
            new Balance(true, List.of("bal", "money"), "", XMoney.ID + ".balance.others"),
            new Pay(true, List.of(), "", 0.01, 0),
            new Baltop(true, List.of("balancetop", "moneytop"), "", 10),
            new Eco(true, List.of("economy"), ECO)),
        new LedgerSettings(365),
        StorageSettings.defaults(XMoney.ID + "_"),
        NetworkSettings.DEFAULTS,
        UpdateCheckerSettings.DEFAULTS);

    public static final ConfigSpec<XMConfig> SPEC = ConfigSpec.builder(XMConfig.class, DEFAULTS)
        .header(
            XMoney.NAME + ": the economy, and Vault, in one plugin.",
            "Docs: https://docs.xenastudios.co/" + XMoney.ID + "/configuration/",
            "",
            "Apply changes with /" + XMoney.ID + " reload. A bad value falls back to its default with a",
            "warning in the console; " + XMoney.NAME + " never stops over config. Messages are MiniMessage:",
            "https://docs.xenastudios.co/start/formatting/")
        .version(1)
        .build();
}
