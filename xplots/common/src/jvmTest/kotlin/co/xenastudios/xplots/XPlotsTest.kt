package co.xenastudios.xplots

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.xplots.api.Access
import co.xenastudios.xplots.api.ChangeResult
import co.xenastudios.xplots.api.RentKind
import co.xenastudios.xplots.api.event.MemberAddEvent
import co.xenastudios.xplots.api.event.PlotClaimEvent
import co.xenastudios.xplots.api.event.PlotUnclaimedEvent
import co.xenastudios.xplots.api.event.RentPaidEvent
import co.xenastudios.xplots.model.Box
import co.xenastudios.xplots.model.Field
import co.xenastudios.xplots.model.Plot
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class XPlotsTest {
    private val harnesses = ArrayList<Harness>()

    private fun harness(config: String = "", hooks: FakeHooks = FakeHooks(), folder: java.nio.file.Path? = null): Harness =
        (if (folder == null) Harness(config = config, hooks = hooks) else Harness(folder, config, hooks)).also { harnesses += it }.start()

    @AfterTest
    fun stop() {
        harnesses.forEach { runCatching { it.stop() } }
    }

    private fun Harness.rentAsMenu(player: PlayerRef, plot: Plot) {
        plugin.signClicked(player, server.name(player)!!, plot)
        server.menus.click(player, 11)
        platform.until("the rent to settle") { !plot.isFree || server.said(player).isNotEmpty() }
    }

    @Test
    fun aFieldIsSplitIntoPlotsWithSignsAndSavedBlocks() {
        val h = harness()
        h.server.blocks[At("world", 201, 65, 201)] = "minecraft:stone"
        val field = h.field()
        assertEquals(2, field.plots().size)
        for (plot in field.plots()) {
            val s = plot.sign
            val sign = h.server.signs[At("world", s.x, s.y, s.z)]
            assertNotNull(sign, "${plot.id}'s sign")
            assertEquals("FOR RENT", sign.lines[0])
            assertEquals(plot.id, sign.lines[1])
        }
        h.flush()
        assertEquals(1, h.query("SELECT COUNT(*) FROM xplots_field")[0][0].toString().toInt())
        assertEquals(2, h.query("SELECT COUNT(*) FROM xplots_plot")[0][0].toString().toInt())
        assertEquals(2, h.query("SELECT COUNT(*) FROM xplots_snapshot")[0][0].toString().toInt())
        assertEquals("1", h.query("SELECT value FROM xplots_meta WHERE name = 'schema'")[0][0], "the schema stays 1.x's")
        assertNotNull(h.server.regionsMade["xplots-box"], "WorldGuard's region")
        // The grid is arithmetic: the path between the plots is no plot.
        assertEquals("box-1", h.plugin.plots.at("world", 200, 65, 200)?.id)
        assertEquals(Field.PATH, h.plugin.plots.at("world", 205, 65, 200))
        assertNull(h.plugin.plots.at("world", 100, 65, 100))
    }

    @Test
    fun aPlayerRentsThroughTheEconomyAndAMemberMayBuild() {
        val h = harness()
        val field = h.field()
        val plot = field.plots()[0]
        val steve = h.server.join("Steve")
        val alex = h.server.join("Alex")
        val stranger = h.server.join("Eve")
        h.economy.balances[steve] = 250.0
        h.rentAsMenu(steve, plot)
        assertEquals(steve, plot.lease?.owner)
        assertEquals(150.0, h.economy.balances[steve])
        assertTrue(h.economy.payments.any { it.second == "xPlots: rent for box-1" })
        assertTrue(h.server.said(steve).any { it.contains("You rented box-1") }, h.server.said(steve).toString())
        val s = plot.sign
        assertEquals("RENTED", h.server.signs[At("world", s.x, s.y, s.z)]!!.lines[0])

        h.run("plots add Alex", h.server.source(steve))
        assertTrue(alex in plot.lease!!.members)
        val p = h.plugin.protection
        assertTrue(p.mayBuild(alex, false, "world", 201, 66, 201), "a member builds")
        assertTrue(p.mayBuild(steve, false, "world", 201, 66, 201), "the owner builds")
        assertFalse(p.mayBuild(stranger, false, "world", 201, 66, 201), "a stranger doesn't")
        assertFalse(p.mayBuild(steve, false, "world", 205, 66, 201), "nobody builds on the path")
        assertFalse(p.mayBuild(steve, false, "world", s.x, s.y, s.z), "nor on a sign")
        assertTrue(p.mayBuild(stranger, true, "world", 201, 66, 201), "xplots.bypass builds anywhere")
        assertTrue(p.mayBuild(stranger, false, "world", 100, 66, 100), "outside every field is free")
        assertTrue(p.crosses("world", 205, 65, 201, "world", 204, 65, 201), "water from the path into a plot")
        assertFalse(p.crosses("world", 205, 65, 201, "world", 205, 65, 202), "path to path is fine")
        assertFalse(p.crosses("world", 201, 65, 201, "world", 202, 65, 201), "inside one plot is fine")

        // A second plot is past max-plots.
        h.rentAsMenu(steve, field.plots()[1])
        assertTrue(field.plots()[1].isFree)
        assertTrue(h.server.said(steve).any { it.contains("You can't rent more than 1 plot") }, h.server.said(steve).toString())
    }

    @Test
    fun rentRunsOutAndThePlotIsPutBackAsItWasMade() {
        val h = harness()
        h.server.blocks[At("world", 201, 65, 201)] = "minecraft:stone"
        val plot = h.field().plots()[0]
        val steve = h.server.join("Steve")
        h.economy.balances[steve] = 1000.0
        h.rentAsMenu(steve, plot)
        h.server.blocks[At("world", 202, 66, 202)] = "minecraft:chest"
        h.server.blocks.remove(At("world", 201, 65, 201))
        h.platform.advance(3.days + 1.minutes)
        h.platform.until("the plot to be put back") { plot.isFree && !h.plugin.isResetting(plot) && h.server.blocks[At("world", 201, 65, 201)] == "minecraft:stone" }
        assertNull(h.server.blocks[At("world", 202, 66, 202)], "what was built is gone")
        assertTrue(h.server.said(steve).any { it.contains("rent ran out") }, h.server.said(steve).toString())
    }

    @Test
    fun payingAheadStopsAtMaxPrepaid() {
        val h = harness()
        val plot = h.field().plots()[0]
        val steve = h.server.join("Steve")
        h.economy.balances[steve] = 10_000.0
        h.rentAsMenu(steve, plot)
        val before = plot.lease!!.expires
        h.plugin.rentals.payRent(steve, plot, 1)
        h.platform.pump()
        assertEquals(before + 3.days.inWholeMilliseconds, plot.lease!!.expires)
        // 6 days paid, a week the most: another 3 would be 9.
        h.plugin.rentals.payRent(steve, plot, 1)
        h.platform.pump()
        assertEquals(before + 3.days.inWholeMilliseconds, plot.lease!!.expires)
        assertTrue(h.server.said(steve).any { it.contains("at most") }, h.server.said(steve).toString())
    }

    @Test
    fun aRenewalIsChargedOnceAnHourBeforeAndTheOwnerToldWhenTheyJoin() {
        val h = harness("plots:\n  auto-renew: true\n")
        val plot = h.field().plots()[0]
        val steve = h.server.join("Steve")
        h.economy.balances[steve] = 1000.0
        h.rentAsMenu(steve, plot)
        val first = plot.lease!!.expires
        assertTrue(h.plugin.renewals.active(plot))
        h.server.leave(steve)
        h.platform.advance(3.days - 1.hours + 1.seconds)
        h.platform.until("the renewal") { plot.lease!!.expires > first }
        assertEquals(first + 3.days.inWholeMilliseconds, plot.lease!!.expires)
        assertEquals(800.0, h.economy.balances[steve], "rent and one renewal")
        assertEquals(1, h.economy.payments.count { "#$first" in it.second })
        h.flush()
        assertEquals(null, h.query("SELECT charge FROM xplots_renewal")[0][0], "the charge is settled")
        // Told as they come back.
        h.server.join("Steve")
        h.plugin.joined(steve, "Steve")
        assertTrue(h.server.said(steve).any { it.contains("Renewed box-1") }, h.server.said(steve).toString())
        // Turned off, it runs out.
        h.plugin.renewals.toggle(steve, plot)
        assertFalse(h.plugin.renewals.active(plot))
    }

    @Test
    fun anAwayOwnersRenewalNoticeSurvivesARestartAndIsToldOnceAsTheyJoin() {
        val folder = java.nio.file.Files.createTempDirectory("xplots")
        val hooks = FakeHooks()
        val economy = hooks.economy as FakeEconomy
        val h = harness("plots:\n  auto-renew: true\n", hooks, folder)
        val plot = h.field().plots()[0]
        val steve = h.server.join("Steve")
        economy.balances[steve] = 1000.0
        h.rentAsMenu(steve, plot)
        val first = plot.lease!!.expires
        h.server.leave(steve)
        h.platform.advance(3.days - 1.hours + 1.seconds)
        h.platform.until("the renewal") { plot.lease!!.expires > first }
        h.flush()
        assertEquals("renewed", h.query("SELECT kind FROM xplots_notice")[0][0], "kept with the plot")
        h.stop()
        harnesses.remove(h)

        // Restarted before Steve came back: told as they join, once.
        val again = harness("plots:\n  auto-renew: true\n", hooks, folder)
        again.platform.until("the plots to be read") { again.plugin.isReady }
        again.server.join("Steve")
        again.plugin.joined(steve, "Steve")
        val renewed = again.server.said(steve).filter { it.contains("Renewed box-1") }
        assertEquals(1, renewed.size, again.server.said(steve).toString())
        again.flush()
        assertEquals(0, again.query("SELECT COUNT(*) FROM xplots_notice")[0][0].toString().toInt(), "forgotten once told")
        again.plugin.joined(steve, "Steve")
        assertEquals(1, again.server.said(steve).count { it.contains("Renewed box-1") }, "not told twice")
    }

    @Test
    fun aRenewalCutShortByARestartIsNeverChargedTwice() {
        val folder = java.nio.file.Files.createTempDirectory("xplots")
        val hooks = FakeHooks()
        val economy = hooks.economy as FakeEconomy
        val h = harness("plots:\n  auto-renew: true\n", hooks, folder)
        val plot = h.field().plots()[0]
        val steve = h.server.join("Steve")
        economy.balances[steve] = 1000.0
        h.rentAsMenu(steve, plot)
        val first = plot.lease!!.expires
        // The payment is taken, and the server stops before xPlots hears it went through.
        economy.hold = true
        h.platform.advance(3.days - 1.hours + 1.seconds)
        h.platform.until("the charge to be taken") { economy.held != null }
        h.flush()
        assertEquals(first, (h.query("SELECT charge FROM xplots_renewal")[0][0] as Number).toLong(), "the charge is written down first")
        h.stop()
        harnesses.remove(h)
        economy.hold = false

        val again = harness("plots:\n  auto-renew: true\n", hooks, folder)
        val reread = again.plugin.plots.plot("box-1")!!
        again.platform.until("the renewal to be settled") { reread.lease!!.expires > first }
        assertEquals(first + 3.days.inWholeMilliseconds, reread.lease!!.expires)
        assertEquals(800.0, economy.balances[steve], "charged once, not twice")
        again.flush()
        assertEquals(null, again.query("SELECT charge FROM xplots_renewal")[0][0])
    }

    @Test
    fun aRenewalTheBalanceDoesntCoverIsTriedOnceAndThePlotRunsOut() {
        val h = harness("plots:\n  auto-renew: true\n")
        val plot = h.field().plots()[0]
        val steve = h.server.join("Steve")
        h.economy.balances[steve] = 100.0
        h.rentAsMenu(steve, plot)
        h.platform.advance(3.days - 1.hours + 1.seconds)
        h.platform.until("the renewal to fail") { h.server.said(steve).any { it.contains("Couldn't renew") } }
        h.platform.advance(2.hours)
        h.platform.until("the plot to run out") { plot.isFree }
        assertEquals(0.0, h.economy.balances[steve])
    }

    @Test
    fun theApiChangesPlotsBehindCancellableEvents() {
        val h = harness()
        val plot = h.field().plots()[0]
        val steve = h.server.join("Steve")
        val alex = h.server.join("Alex")
        var refuse = true
        h.plugin.events.subscribe(PlotClaimEvent.TYPE) { if (refuse) it.isCancelled = true }
        assertEquals(ChangeResult.CANCELLED, h.platform.await(h.plugin.setOwner("box-1", steve, "Steve")))
        assertTrue(plot.isFree)
        refuse = false
        assertEquals(ChangeResult.DONE, h.platform.await(h.plugin.setOwner("box-1", steve, "Steve")))
        assertEquals(steve, h.plugin.plot("BOX-1")!!.owner)
        h.plugin.events.subscribe(MemberAddEvent.TYPE) { it.isCancelled = it.name == "Eve" }
        assertEquals(ChangeResult.DONE, h.platform.await(h.plugin.addMember("box-1", alex, "Alex")))
        assertEquals(ChangeResult.CANCELLED, h.platform.await(h.plugin.addMember("box-1", h.server.join("Eve"), "Eve")))
        val unclaimed = ArrayList<String>()
        h.plugin.events.subscribe(PlotUnclaimedEvent.TYPE) { unclaimed += it.plot.ownerName!! }
        assertEquals(ChangeResult.DONE, h.platform.await(h.plugin.evict("box-1")))
        assertEquals(listOf("Steve"), unclaimed)
        assertEquals(ChangeResult.NO_PLOT, h.platform.await(h.plugin.evict("box-9")))
        assertEquals(listOf("box"), h.plugin.fields().map { it.name })
        assertEquals("box-2", h.plugin.plotAt(WorldRef.of("world"), 208, 66, 202)?.id)
    }

    @Test
    fun addOnsModifyPricesAndDecideAccess() {
        val h = harness()
        val plot = h.field().plots()[0]
        val steve = h.server.join("Steve")
        val alex = h.server.join("Alex")
        h.plugin.registerPriceModifier("Ranks") { _, _, kind, price -> if (kind == RentKind.RENT) price / 2 else price }
        assertEquals(50.0, h.plugin.price(steve, "box-1", RentKind.RENT, 1))
        h.economy.balances[steve] = 50.0
        val paid = ArrayList<Double>()
        h.plugin.events.subscribe(co.xenastudios.xplots.api.event.PlotClaimedEvent.TYPE) { paid += it.price }
        h.rentAsMenu(steve, plot)
        assertEquals(listOf(50.0), paid)
        h.plugin.registerAccessRule("Contest") { player, p, _, _, _, _ -> if (player == alex && p?.id == "box-1") Access.ALLOW else Access.DEFAULT }
        assertTrue(h.plugin.protection.mayBuild(alex, false, "world", 201, 66, 201))
        h.plugin.unregisterAll("Contest")
        assertFalse(h.plugin.protection.mayBuild(alex, false, "world", 201, 66, 201))
        // A region backend hears of every field at once.
        val defined = ArrayList<String>()
        h.plugin.registerRegionBackend("Claims", object : co.xenastudios.xplots.api.RegionBackend {
            override fun define(id: String, world: WorldRef, columns: co.xenastudios.xplots.api.PlotBox) { defined += id }
            override fun remove(id: String, world: WorldRef) { defined -= id }
        })
        assertEquals(listOf("xplots-box"), defined)
    }

    @Test
    fun plotHomesAreListedInXTeleportAndThePickerLeavesVanishedPlayersOut() {
        val h = harness()
        val plot = h.field().plots()[0]
        val steve = h.server.join("Steve")
        h.server.join("Alex")
        val hidden = h.server.join("Ghost")
        h.hooks.vanished += hidden
        h.economy.balances[steve] = 100.0
        h.rentAsMenu(steve, plot)
        val homes = h.hooks.homes!!(steve)
        assertEquals(listOf("box-1"), homes.map { it.name })
        assertEquals(front(plot).blockX, homes[0].location.blockX)
        // The picker: Alex, not the vanished Ghost.
        h.plugin.ui.plot(steve, plot)
        h.server.menus.click(steve, 14)
        h.server.menus.click(steve, 30)
        val names = h.server.menus.icons(steve).mapNotNull { it?.name?.plain() }
        assertTrue("Alex" in names, names.toString())
        assertFalse("Ghost" in names, names.toString())
    }

    @Test
    fun ownersRollAMembersChangesBackInTheirPlotOnly() {
        val h = harness()
        val plot = h.field().plots()[0]
        val steve = h.server.join("Steve", perms = arrayOf("xplots.rollback"))
        var asked: Triple<String, Box, String>? = null
        h.hooks.rewind = object : Rewind {
            override fun rollback(world: String, box: Box, playerName: String, seconds: Long) =
                pendingOf<Outcome<Int, String>>(Outcome.Ok(7)).also { asked = Triple(world, box, "$playerName/$seconds") }
        }
        h.economy.balances[steve] = 100.0
        h.rentAsMenu(steve, plot)
        h.run("plots rollback Alex 2h", h.server.source(steve)) { h.server.said(steve).any { it.contains("Undid") } }
        assertEquals(Triple("world", plot.box, "Alex/7200"), asked)
    }

    @Test
    fun itemCurrencyIsTakenFromTheInventory() {
        val h = harness("currency:\n  type: item\n")
        val plot = h.field().plots()[0]
        val steve = h.server.join("Steve")
        h.server.inventory[steve] = 120
        h.rentAsMenu(steve, plot)
        assertEquals(steve, plot.lease?.owner)
        assertEquals(20, h.server.inventory[steve])
        assertFalse(h.plugin.renewals.offered(plot), "an item can't be taken from someone offline")
    }

    @Test
    fun staffCommandsWork() {
        val h = harness()
        h.field()
        h.run("xplots field set box price 250")
        assertEquals("250", h.plugin.plots.field("box")!!.overrides["price"])
        h.run("xplots field set box sign-position middle")
        assertTrue(h.console.replies.any { it.contains("Moved 2 signs") }, h.console.replies.toString())
        h.run("xplots plot info box-2")
        assertTrue(h.console.replies.last().contains("free."), h.console.replies.last())
        h.run("xplots plot setowner box-1 Steve")
        assertTrue(h.console.replies.last().contains("has never played here"))
        h.server.join("Steve")
        h.run("xplots plot setowner box-1 Steve")
        assertEquals("Steve", h.plugin.plots.plot("box-1")!!.lease?.ownerName)
        h.run("xplots field list")
        assertTrue(h.console.replies.any { it.contains("1 of 2 rented") }, h.console.replies.toString())
        h.run("xplots reload")
        h.run("xplots field delete box")
        h.run("xplots confirm") { h.console.replies.any { it.contains("Deleted box") } }
        assertTrue(h.plugin.plots.isEmpty)
        assertTrue(h.server.signs.isEmpty())
        assertNull(h.server.regionsMade["xplots-box"])
    }

    @Test
    fun nothingCanBeChangedInAFieldBeforeThePlotsAreRead() {
        val h = Harness()
        val steve = h.server.join("Steve")
        assertFalse(h.plugin.protection.mayBuild(steve, false, "world", 0, 64, 0))
        assertTrue(h.plugin.protection.crosses("world", 0, 64, 0, "world", 1, 64, 0))
        h.start()
        harnesses += h
        assertTrue(h.plugin.protection.mayBuild(steve, false, "world", 0, 64, 0))
    }
}
