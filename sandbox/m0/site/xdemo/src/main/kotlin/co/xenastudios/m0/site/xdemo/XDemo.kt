package co.xenastudios.m0.site.xdemo

import co.xenastudios.m0.site.core.ConfigSpec
import co.xenastudios.m0.site.core.MessageSpec
import co.xenastudios.m0.site.core.PermissionDefault
import co.xenastudios.m0.site.core.PluginDefinitionProvider
import co.xenastudios.m0.site.core.xenaPlugin

// xDemo: a fake coins plugin, declared once. The handlers are the plugin; the rest is what
// the docs read. Nothing here is written for the docs alone.

object XDemoConfig : ConfigSpec() {
    val startingCoins = integer("starting-coins", 100, "Coins a player has on their first join.", min = 0)
    val maxPay = integer("max-pay", 10_000, "The most one /pay can send. 0 for no limit.", min = 0)

    val storage = section("storage", "Where balances are kept.")
    val storageType = choice("storage.type", "sqlite", listOf("sqlite", "mysql"), "sqlite: a file in the plugin's folder. mysql: MySQL or MariaDB.", reloadable = false)
    val storageHost = string("storage.host", "localhost", "For mysql only.", reloadable = false)
    val storagePort = integer("storage.port", 3306, min = 1, max = 65535, reloadable = false)

    val pay = section("pay", "Payments between players.")
    val payCooldown = duration("pay.cooldown", "5s", "How long a player waits between payments.")
    val payBlockedWorlds = list("pay.blocked-worlds", listOf("event_world"), "Worlds where /pay is refused.")

    val updateChecker = boolean("update-checker", true, "Checks Modrinth for a new version once a day.")
}

object XDemoMessages : MessageSpec() {
    val balance = message("balance.self", "<muted>You have <accent><coins></accent> coins.", "The reply to /coins.", "coins")
    val balanceOther = message("balance.other", "<muted><player> has <accent><coins></accent> coins.", "The reply to /coins <player>.", "player", "coins")
    val paid = message("pay.sent", "<success>Sent <coins> coins to <player>.", "To the payer.", "coins", "player")
    val received = message("pay.received", "<success><player> sent you <coins> coins.", "To the player paid.", "player", "coins")
    val cooldown = message("pay.cooldown", "<error>Wait <time> before paying again.", "When /pay is on cooldown.", "time")
    val given = message("admin.given", "<success>Gave <coins> coins to <player>.", "The reply to /xdemo give.", "coins", "player")
}

object XDemo : PluginDefinitionProvider {
    private val balances = mutableMapOf<String, Int>()
    private fun coins(name: String) = balances.getOrPut(name) { XDemoConfig.startingCoins.default }

    override val definition = xenaPlugin("xdemo", "xDemo", "A fake coins plugin for the M0 site spike.") {
        val coinsSelf = permission("coins", "See your own coins.", PermissionDefault.EVERYONE)
        val coinsOthers = permission("coins.others", "See another player's coins.", children = mapOf("xdemo.coins" to true))
        val pay = permission("pay", "Send coins to another player.", PermissionDefault.EVERYONE)
        val give = permission("admin.give", "Give coins with /xdemo give.")

        config(XDemoConfig)
        messages(XDemoMessages)

        command("coins", "Shows your coins, or another player's.", coinsSelf, aliases = listOf("bal", "balance")) {
            player("player", "Whose coins to show. Needs xdemo.coins.others.", required = false)
            executes { ctx ->
                val who = ctx.args["player"] as String? ?: ctx.sender.name
                "${who}: ${coins(who)}"
            }
        }
        command("pay", "Sends coins to another player.", pay, playerOnly = true) {
            player("player", "Who to pay.")
            integer("amount", "How many coins, up to max-pay.", min = 1)
            executes { ctx ->
                val to = ctx.args["player"] as String
                val amount = ctx.args["amount"] as Int
                balances[ctx.sender.name] = coins(ctx.sender.name) - amount
                balances[to] = coins(to) + amount
                "Sent $amount to $to"
            }
        }
        command("xdemo", "xDemo's admin commands.") {
            subcommand("give", "Gives a player coins.", give) {
                player("player", "Who to give coins to.")
                integer("amount", "How many.", min = 1, max = 1_000_000)
                executes { ctx ->
                    val to = ctx.args["player"] as String
                    balances[to] = coins(to) + ctx.args["amount"] as Int
                    "Gave ${ctx.args["amount"]} to $to"
                }
            }
        }

        placeholder("coins", "The player's coins, formatted for their language.", "1,250") { coins(it.name).toString() }
        placeholder("rank", "The player's place on the coins leaderboard.", "3") { "1" }

        event("co.xenastudios.xdemo.api.event.CoinsChangeEvent", "Before a player's coins change. Cancel it to stop the change.", cancellable = true, since = "2.0.0") {
            field("player", "PlayerRef", "Whose coins change.")
            field("from", "Long", "The balance before.")
            field("to", "Long", "The balance after; settable.")
            field("cause", "ChangeCause", "PAY, ADMIN or API.")
        }
        event("co.xenastudios.xdemo.api.event.PaymentEvent", "After a payment between two players went through.", cancellable = false, since = "2.0.0") {
            field("from", "PlayerRef", "Who paid.")
            field("to", "PlayerRef", "Who was paid.")
            field("amount", "Long", "How many coins.")
        }
    }
}
