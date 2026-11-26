package co.xenastudios.xmoney

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandResult
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakeDatabase
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.core.testing.settled
import co.xenastudios.xmoney.api.Amount
import co.xenastudios.xmoney.api.TransactionHook
import co.xenastudios.xmoney.api.TransactionResult
import co.xenastudios.xmoney.api.XMoney
import co.xenastudios.xmoney.api.event.BalanceChangedEvent
import co.xenastudios.xmoney.api.event.PreTransactionEvent
import co.xenastudios.xmoney.config.XMoneyConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/** xMoney's commands and API on the fake platform, with a database that keeps nothing: memory is the truth on one server. */
class XMoneyTest {
    private val platform = FakePlatform()
    private val server = FakeMoneyServer()
    private val loaded = Loaded(XMoneyConfig.defaults, Translations.of(XMoneyMessages.catalog, emptyMap()))
    private val plugin = XMoneyPlugin(platform, server, { loaded }, server)
    private val steve = FakeCommandSource.player("Steve")
    private val alex = FakeCommandSource.player("Alex")
    private val console = FakeCommandSource.console()

    init {
        platform.scheduler.runAs(Where.Global) { plugin.start(loaded) }
        plugin.open(Outcome.Ok<Database>(FakeDatabase(prefix = "xmoney_")), network = false)
        platform.scheduler.runPending()
        for (player in listOf(steve, alex)) {
            server.join(player.player!!, player.name)
            plugin.joined(player.player!!, player.name)
        }
    }

    @AfterTest
    fun stop() {
        platform.scheduler.runAs(Where.Global) { plugin.stop() }
    }

    private fun run(source: FakeCommandSource, line: String): CommandResult {
        source.clear()
        val result = platform.scheduler.runAs(Where.Global) { plugin.dispatcher.dispatch(source, line) }
        // What the platform's registrar tells the sender of a command that didn't run.
        plugin.texts.feedback.message(result, source)?.let(source::reply)
        platform.scheduler.runPending()
        return result
    }

    private fun units(text: String) = Amount.parse(text)!!.units

    @Test
    fun theDatabaseOpensAndTheApiIsThere() {
        assertTrue(plugin.ready.settled())
        assertTrue(XMoney.isEnabled())
        assertEquals("2.0.0", XMoney.api().version)
    }

    @Test
    fun balanceAndPayAndBaltop() {
        run(console, "eco give Steve 100")
        assertEquals(listOf("» Gave Steve $100.00. They have $100.00."), console.replies)
        run(steve, "balance")
        assertEquals(listOf("» Balance: $100.00"), steve.replies)
        run(steve, "pay Alex 12.5")
        assertEquals(listOf("» Sent $12.50 to Alex. You have $87.50 left."), steve.replies)
        assertEquals(listOf("» Received $12.50 from Steve."), server.sent[alex.player!!]?.toList())
        run(steve, "pay Alex 1000")
        assertEquals(listOf("» You don't have $1,000.00. You have $87.50."), steve.replies)
        run(steve, "pay Steve 1")
        assertEquals(listOf("» You can't pay yourself."), steve.replies)
        run(steve, "pay Alex 0.001")
        assertEquals(listOf("» Amounts have at most 2 decimals."), steve.replies)
        run(steve, "pay Nobody 1")
        assertEquals(listOf("» Nobody has never played here."), steve.replies)
        run(steve, "baltop")
        assertEquals(listOf("» Richest players. All 2: $100.00", "#1 Steve $87.50", "#2 Alex $12.50"), steve.replies)
        // Someone else's balance needs xmoney.balance.others.
        run(steve, "balance Alex")
        assertEquals(listOf("» You don't have permission to do that."), steve.replies)
        run(console, "bal Alex")
        assertEquals(listOf("» Alex's balance: $12.50"), console.replies)
    }

    @Test
    fun ecoTakesSetsAndResets() {
        run(console, "eco give Alex 50")
        run(console, "eco take Alex 1000")
        assertEquals(listOf("» Alex only has $50.00."), console.replies)
        run(console, "eco take Alex 20")
        assertEquals(listOf("» Took $20.00 from Alex. They have $30.00."), console.replies)
        run(console, "eco set Alex 10.5")
        assertEquals(listOf("» Set Alex's balance to $10.50."), console.replies)
        run(console, "eco reset Alex")
        assertEquals(listOf("» Reset Alex's balance to $0.00."), console.replies)
        run(console, "eco give Alex 1.5k")
        assertEquals(units("1500"), plugin.balanceOf(alex.player!!))
    }

    @Test
    fun aPaymentOverConfirmAboveAsksFirst() {
        val config = co.xenastudios.core.common.config.Configs.load(
            XMoneyConfig, "commands:\n  pay:\n    confirm-above: 10\n", co.xenastudios.core.testing.TestYaml,
        ).let { (it as Outcome.Ok).value.config }
        platform.scheduler.runAs(Where.Global) { plugin.apply(Loaded(config, loaded.translations)) }
        run(console, "eco give Steve 100")
        run(steve, "pay Alex 20")
        assertEquals(listOf("» Send $20.00 to Alex? [Confirm]"), steve.replies)
        assertEquals(units("100"), plugin.balanceOf(steve.player!!))
        run(steve, "pay Alex 20 confirm")
        assertEquals(units("80"), plugin.balanceOf(steve.player!!))
    }

    @Test
    fun theApiPaysWithCausesAndHooksAndEventsHaveTheirSay() {
        val api = XMoney.api()
        val stranger = PlayerRef.of(Uuid.random())
        val changed = ArrayList<BalanceChangedEvent>()
        api.events.subscribe(BalanceChangedEvent.TYPE) { changed += it }
        assertEquals(TransactionResult.Status.SUCCESS, api.deposit("xVote", stranger, Amount.of(5), "vote reward").settled().status)
        assertTrue(api.hasAccount(stranger))
        assertFalse(api.has(stranger, Amount.of(6)))
        api.events.subscribe(PreTransactionEvent.TYPE) { if (it.source == "Blocked") it.isCancelled = true }
        assertEquals(TransactionResult.Status.CANCELLED, api.deposit("Blocked", stranger, Amount.of(1), "").settled().status)
        val hook = TransactionHook { if (it.change.isNegative && it.source == "xMarket") "<error>Trades are locked." else null }
        api.registerHook("xPlots", hook)
        assertEquals(TransactionResult.Status.CANCELLED, api.withdraw("xMarket", stranger, Amount.of(1), "trade").settled().status)
        api.unregisterHook(hook)
        assertEquals(TransactionResult.Status.INSUFFICIENT_FUNDS, api.withdraw("xMarket", stranger, Amount.of(6), "trade").settled().status)
        assertEquals(TransactionResult.Status.SUCCESS, api.transfer("xPlots", stranger, steve.player!!, Amount.of(2), "rent").settled().status)
        platform.scheduler.runPending()
        assertEquals(listOf("vote reward", "rent", "rent"), changed.map { it.reason })
        assertEquals(Amount.of(-2), changed[1].change)
        assertEquals("$3.00", api.format(api.balance(stranger)))
        assertEquals(listOf(stranger, steve.player), api.top(1, 2).map { it.player })
    }

    @Test
    fun placeholdersReadMemory() {
        run(console, "eco give Steve 1234.5")
        assertEquals("$1,234.50", plugin.placeholders.resolve("balance", steve.player))
        assertEquals("1234.50", plugin.placeholders.resolve("balance_raw", steve.player))
        assertEquals("1.2k", plugin.placeholders.resolve("balance_short", steve.player))
        assertEquals("Steve", plugin.placeholders.resolve("top_1_name", null))
        assertEquals("$1,234.50", plugin.placeholders.resolve("top_1_balance", null))
        assertEquals("", plugin.placeholders.resolve("top_3_name", null))
        assertEquals(null, plugin.placeholders.resolve("top_101_name", null))
    }
}
