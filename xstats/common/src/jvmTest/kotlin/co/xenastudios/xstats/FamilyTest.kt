package co.xenastudios.xstats

import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.testing.RecordingLog
import co.xenastudios.xmoney.api.Amount
import co.xenastudios.xmoney.api.TransactionKind
import co.xenastudios.xmoney.api.XMoney
import co.xenastudios.xmoney.api.XMoneyApi
import co.xenastudios.xmoney.api.event.BalanceChangedEvent
import co.xenastudios.xstats.api.Period
import co.xenastudios.xvote.api.XVote
import co.xenastudios.xvote.api.XVoteApi
import co.xenastudios.xvote.api.event.VoteRewardedEvent
import java.lang.reflect.Proxy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The family's stats: xStats hears xVote's and xMoney's events on their own buses, through their
 * APIs found by name, as on a server where each plugin has its own copy of the core, and counts them
 * as stats of theirs. Each API here is the real one, its plugin stood in for by a [Proxy].
 */
class FamilyTest {
    private val harness = Harness().start()
    private val voteBus = SimpleEventBus(RecordingLog())
    private val moneyBus = SimpleEventBus(RecordingLog())

    private inline fun <reified T> api(bus: SimpleEventBus): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        if (method.name == "getEvents") bus else throw UnsupportedOperationException(method.name)
    } as T

    @AfterTest
    fun stop() {
        XVote.register(null)
        XMoney.register(null)
        harness.stop()
    }

    @Test
    fun theAdaptersListOfTheFamilyIsTheIntegrations() {
        assertEquals(Integrations.SPECS.joinToString(",") { it.plugin }, XStatsPlugin.FAMILY)
    }

    @Test
    fun votesAndMoneyBecomeStats() {
        XVote.register(api<XVoteApi>(voteBus))
        XMoney.register(api<XMoneyApi>(moneyBus))
        val hooks = FamilyHooks(harness.plugin) { FamilyTest::class.java.classLoader }
        hooks.hookAll()
        assertEquals(setOf("xVote", "xMoney"), hooks.names)
        harness.settle()
        val steve = harness.join("Steve").ref
        harness.save()
        voteBus.fire(VoteRewardedEvent.TYPE, VoteRewardedEvent(steve, "PlanetMinecraft.com", listOf("vote")))
        voteBus.fire(VoteRewardedEvent.TYPE, VoteRewardedEvent(steve, "Minecraft-MP.com", listOf("vote")))
        // 25.00 paid to Steve (ten-thousandths in xMoney, hundredths here), then 10.00 taken.
        moneyBus.fire(BalanceChangedEvent.TYPE, BalanceChangedEvent(steve, Amount.of(25), Amount.of(125), TransactionKind.PAY, "xMoney", "pay", null))
        moneyBus.fire(BalanceChangedEvent.TYPE, BalanceChangedEvent(steve, Amount.of(-10), Amount.of(115), TransactionKind.TAKE, "xMoney", "eco take", null))
        harness.save()
        assertEquals(2, harness.plugin.value(steve, "xvote_votes", Period.ALL))
        assertEquals(2, harness.plugin.value(steve, "xvote_votes", Period.DAILY))
        assertEquals(11500, harness.plugin.value(steve, "xmoney_balance", Period.ALL))
        assertEquals(2500, harness.plugin.value(steve, "xmoney_earned", Period.ALL))
        assertEquals("115.00", Placeholders.resolve(harness.plugin, steve, "xmoney_balance"))
        assertTrue(Integrations.describe(harness.plugin).contains("xVote"))
        // xVote goes away: its stat is out of use, its numbers saved.
        hooks.unhook("xVote")
        assertNull(harness.plugin.stat("xvote_votes"))
        voteBus.fire(VoteRewardedEvent.TYPE, VoteRewardedEvent(steve, "PlanetMinecraft.com", listOf("vote")))
        assertEquals(0, harness.plugin.engine.waiting)
        hooks.close()
    }

    @Test
    fun anIntegrationTurnedOffIsntHooked() {
        harness.reload("integrations:\n  xvote: false\n")
        XVote.register(api<XVoteApi>(voteBus))
        val hooks = FamilyHooks(harness.plugin) { FamilyTest::class.java.classLoader }
        hooks.hook("xVote")
        assertEquals(emptySet(), hooks.names)
        assertNull(harness.plugin.stat("xvote_votes"))
    }
}
