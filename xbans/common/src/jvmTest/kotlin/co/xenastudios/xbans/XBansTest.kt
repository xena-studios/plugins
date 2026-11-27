package co.xenastudios.xbans

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventListener
import co.xenastudios.xbans.api.PunishRequest
import co.xenastudios.xbans.api.PunishResult
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.api.event.LoginDeniedEvent
import co.xenastudios.xbans.api.event.PunishEvent
import co.xenastudios.xbans.api.event.PunishedEvent
import co.xenastudios.xbans.store.Backend
import co.xenastudios.xbans.store.find
import co.xenastudios.xbans.store.forget
import co.xenastudios.xbans.store.inForceOf
import co.xenastudios.xbans.store.ipHistory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * xBans on the fake platform with a real SQLite database: 1.x's boot checks and its tests' cases,
 * each waiting on what it awaits (never on ticks or sleeps), so none is flaky (#149) or can hang.
 */
class XBansTest {
    private val h = Harness().start()
    private val server = h.server
    private val steve = FakeBansServer.ref("Steve")
    private val alex = FakeBansServer.ref("Alex")

    init {
        // An offline-mode server: names never seen have a uuid without asking Mojang.
        server.known["steve"] = steve
        server.known["alex"] = alex
    }

    @AfterTest
    fun stop() {
        h.stop()
        h.folder.toFile().deleteRecursively()
    }

    private fun ban(player: PlayerRef) = h.backend.active.ban(player, null, h.plugin.serverName, h.plugin.now())

    private fun history(player: PlayerRef): Long =
        h.platform.await(h.plugin.history(player, 1, 10)).total

    private fun lastConsole() = server.console.last().removePrefix("» ")

    private fun lastReply(source: co.xenastudios.core.common.command.CommandSource = h.console) = h.replies(source).last().removePrefix("» ")

    @Test
    fun banReplacesLongerAndRefusesShorter() {
        h.run("ban Steve -s 7d griefing the spawn")
        assertEquals("[Silent] Console banned Steve for 1w: griefing the spawn", lastConsole())
        assertEquals("griefing the spawn", ban(steve)!!.punishment.reason)
        h.run("ban Steve for good")
        assertEquals("Console banned Steve permanently: for good", lastConsole())
        h.run("ban Steve for good, and why")
        assertEquals("Console banned Steve permanently: for good, and why", lastConsole())
        assertEquals(1, h.backend.active.all(PunishmentType.BAN, null, h.plugin.now()).size)
        h.run("tempban Steve 1d shorter")
        assertTrue(lastReply().contains("is already banned until"), lastReply())
        h.run("pardon Steve appealed")
        assertEquals("Console unbanned Steve.", lastConsole())
        assertNull(ban(steve))
        assertEquals(3, history(steve))
    }

    @Test
    fun aBannedPlayerIsKickedAndTurnedAwayInTheirLanguage() {
        server.join("Steve")
        h.run("ban Steve Hacking") { server.kicked.isNotEmpty() }
        assertEquals("Steve", server.kicked.single().first)
        assertTrue(server.kicked.single().second.startsWith("You are banned from this server."), server.kicked.single().second)
        val ban = h.plugin.banOnJoin(steve, "203.0.113.9")
        assertNotNull(ban)
        var denied: LoginDeniedEvent? = null
        h.plugin.bus.subscribe(LoginDeniedEvent.TYPE, EventListener { denied = it })
        val screen = h.plugin.denied(steve, "Steve", ban).plain()
        assertTrue("Reason: Hacking" in screen, screen)
        h.platform.until("the event") { denied != null }
        assertEquals(steve, denied!!.player)
    }

    @Test
    fun aTempBanEndsOnItsOwn() {
        h.run("tempban Steve 1h cooling off")
        assertNotNull(ban(steve))
        h.platform.advance(59.minutes)
        assertNotNull(h.plugin.banOnJoin(steve, null))
        h.platform.advance(2.minutes)
        assertNull(h.plugin.banOnJoin(steve, null))
    }

    @Test
    fun templatesMutesAndWarnings() {
        h.run("mute Alex spam")
        assertEquals("Console muted Alex for 1h: Spamming", lastConsole())
        h.run("checkmute Alex")
        assertTrue(lastReply().contains("Reason: Spamming"), lastReply())
        h.run("warn Alex 1h language")
        assertEquals("Console warned Alex: Inappropriate language", lastConsole())
        h.run("warnings Alex")
        assertTrue(h.console.replies.any { it.contains("Alex has 1 active warnings") }, h.console.replies.toString())
        h.run("unwarn Alex")
        assertEquals("Console removed a warning from Alex.", lastConsole())
        h.run("unmute Alex")
        assertEquals("Console unmuted Alex.", lastConsole())
    }

    @Test
    fun aMuteBlocksChatAndCommandsByWhatTheyAre() {
        server.join("Alex")
        h.run("mute Alex 1d spam") { h.plugin.muteOf(alex) != null }
        assertNotNull(h.plugin.muteOf(alex))
        assertTrue(h.plugin.blocksCommand("/msg Bob hi"))
        assertTrue(h.plugin.blocksCommand("/essentials:msg Bob hi"))
        assertTrue(h.plugin.blocksCommand("/emsg Bob hi"), "an alias of a blocked command")
        assertTrue(!h.plugin.blocksCommand("/spawn"))
        assertTrue(h.plugin.mutedMessage(alex, h.plugin.muteOf(alex)!!)!!.plain().removePrefix("» ").startsWith("You're muted for"))
    }

    @Test
    fun theThirdWarningRunsItsAction() {
        repeat(3) { h.run("warn Steve 1d number $it") }
        h.platform.until("the action") { server.dispatched.isNotEmpty() }
        assertEquals(listOf("tempmute Steve 1h -s Reached 3 warnings"), server.dispatched)
    }

    @Test
    fun ipBansShowHalfTheAddressAndNeverLoopback() {
        h.run("ipban 198.51.100.7 boot test")
        assertEquals("Console IP-banned 198.51.x.x permanently: boot test", lastConsole())
        assertNotNull(h.plugin.banOnJoin(steve, "198.51.100.7"), "anyone on the IP")
        h.run("ban-ip 127.0.0.1 never loopback")
        assertEquals("That isn't an IP address.", lastReply())
        h.run("unban 198.51.100.7")
        assertEquals("Console unbanned 198.51.x.x.", lastConsole())
    }

    @Test
    fun unbanningByIdKeepsTheHistory() {
        h.run("ban Steve one")
        val id = ban(steve)!!.id
        h.run("unban #$id sorry")
        assertNull(ban(steve))
        val kept = h.platform.await(h.backend.find(id)).getOrNull()!!
        assertEquals("Console", kept.punishment.removal!!.staffName)
        assertEquals("sorry", kept.punishment.removal!!.reason)
    }

    /** #149's guarantee, made deterministic: a dry run first, then only what's no longer in force goes. */
    @Test
    fun pruneDeletesOnlyWhatsOver() {
        server.join("Steve")
        h.run("warn Steve 1h one")
        h.run("kick Steve two")
        h.run("ban $steve three")
        assertEquals(3, history(steve))
        h.run("prunehistory Steve")
        assertTrue(lastReply().startsWith("That removes 1 inactive entries from Steve's history"), lastReply())
        assertEquals(3, history(steve), "a dry run first")
        h.run("prunehistory Steve confirm")
        assertEquals("Removed 1 inactive entries from Steve's history.", lastReply())
        // The kick is gone; the warning and the ban are still in force.
        assertEquals(2, history(steve))
        // Once the warning has ended, it goes too.
        h.platform.advance(2.hours)
        h.run("prunehistory Steve confirm")
        assertEquals(1, history(steve))
    }

    @Test
    fun staffLimitsWeightsAndExemptions() {
        val helper = server.join("Helper", "203.0.113.20", "xbans.ban", "xbans.tempban", "xbans.unban", "xbans.limit.helper")
        helper.weight = 10
        val boss = server.join("Boss", "203.0.113.21")
        boss.weight = 50
        val hs = server.source("Helper")
        h.run("tempban Steve 2d too long", hs)
        assertEquals("You can give at most 1d.", lastReply(hs))
        h.run("ban Boss nope", hs)
        assertEquals("Boss outranks you.", lastReply(hs))
        server.join("Exempt", "203.0.113.22", "xbans.exempt")
        h.run("ban Exempt nope", hs)
        assertEquals("Exempt can't be punished.", lastReply(hs))
        // The console's ban is lifted only by someone with its weight or the override.
        h.run("ban Steve console's")
        h.run("unban Steve", hs)
        assertTrue(lastReply(hs).startsWith("That was given by someone who outranks you."), lastReply(hs))
    }

    @Test
    fun kickNeedsThePlayerOnline() {
        h.run("kick Nobody")
        assertEquals("No player called Nobody is online.", lastReply())
    }

    @Test
    fun theApiPunishesWithItsEventsAndCanBeCancelled() {
        var punished: PunishedEvent? = null
        h.plugin.bus.subscribe(PunishedEvent.TYPE, EventListener { punished = it })
        val made = h.platform.await(h.plugin.punish(PunishRequest.of(PunishmentType.BAN, steve, "Cheating").withDurationMillis(1.days.inWholeMilliseconds)))
        assertEquals(PunishResult.Outcome.DONE, made.outcome)
        assertTrue(h.plugin.isBanned(steve))
        h.platform.until("the event") { punished != null }
        val cancel = h.plugin.bus.subscribe(PunishEvent.TYPE, EventListener { it.isCancelled = true })
        assertEquals(PunishResult.Outcome.CANCELLED, h.platform.await(h.plugin.punish(PunishRequest.of(PunishmentType.MUTE, alex, "Spam"))).outcome)
        cancel.cancel()
        val pardon = h.platform.await(h.plugin.pardon(steve, PunishmentType.BAN, null, "Plugin", "appeal"))
        assertEquals(PunishResult.Outcome.DONE, pardon.outcome)
        assertTrue(!h.plugin.isBanned(steve))
    }

    @Test
    fun aReloadThatTurnsHashingOnHashesWhatsStored() {
        server.join("Steve", "203.0.113.9")
        h.plugin.joined(steve, "Steve", "203.0.113.9")
        h.run("ipban 198.51.100.7 hashed later")
        h.reload("ips:\n  hash: true\n")
        h.platform.until("the hashed database") { h.plugin.backend?.keys?.hashing == true }
        assertNotNull(h.plugin.banOnJoin(alex, "198.51.100.7"), "the IP ban still matches, by its hash")
        assertTrue(h.platform.storage.paths.contains("ip-salt.txt"))
        val stored = h.platform.await(h.backend.inForceOf(PunishmentType.BAN, null, h.backend.keys.key("198.51.100.7"), h.plugin.now())).getOrNull()!!
        assertEquals(1, stored.size)
        assertTrue(stored.single().punishment.targetName.endsWith("…"), "no part of the address is left: ${stored.single().punishment.targetName}")
    }

    @Test
    fun forgetLeavesThePunishments() {
        server.join("Steve", "203.0.113.9")
        h.plugin.joined(steve, "Steve", "203.0.113.9")
        h.platform.until("the join to be recorded") { h.platform.await(h.backend.ipHistory(steve)).getOrNull()!!.isNotEmpty() }
        h.run("ban Steve kept")
        val forgotten = h.platform.await(h.backend.forget(steve)).getOrNull()!!
        assertEquals(2, forgotten, "the player's row and their IP")
        assertEquals(1, history(steve))
        assertEquals(Backend.TOPIC_PUNISH, "punish")
    }
}
