package co.xenastudios.xteleport

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.event.EventListener
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandResult
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakeDatabase
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.core.testing.settled
import co.xenastudios.xteleport.api.DestinationProvider
import co.xenastudios.xteleport.api.TeleportOptions
import co.xenastudios.xteleport.api.TeleportResult
import co.xenastudios.xteleport.api.VisibilityHook
import co.xenastudios.xteleport.api.XTeleport
import co.xenastudios.xteleport.api.event.PreTeleportEvent
import co.xenastudios.xteleport.config.XTeleportConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** xTeleport's commands and API on the fake platform (Folia's regions, virtual time), with a database that keeps nothing: memory is the truth here. */
class XTeleportTest {
    private val platform = FakePlatform()
    private val server = FakeTeleportServer(platform.scheduler)
    private val world = WorldRef.of("world")
    private var config: Config = XTeleportConfig.defaults
    private val plugin = XTeleportPlugin(platform, server, { loaded() }, server, server)
    private val steve = FakeCommandSource.player("Steve")
    private val alex = FakeCommandSource.player("Alex")
    private val console = FakeCommandSource.console()

    private fun loaded() = Loaded(config, Translations.of(XTeleportMessages.catalog, emptyMap()))

    init {
        platform.scheduler.runAs(Where.Global) { plugin.start(loaded()) }
        plugin.open(Outcome.Ok<Database>(FakeDatabase(prefix = "xteleport_")))
        platform.scheduler.runPending()
        // What a server gives everyone: the permissions declared for everyone.
        val everyone = plugin.permissions.permissions.filter { it.default == co.xenastudios.core.common.permission.PermissionDefault.EVERYONE }.map { it.node }
        steve.permissions += everyone
        alex.permissions += everyone
        server.join(steve)
        server.join(alex, Location(world, 100.5, 64.0, 100.5))
        platform.scheduler.runPending()
    }

    @AfterTest
    fun stop() {
        platform.scheduler.runAs(Where.Global) { plugin.stop() }
    }

    private fun run(source: FakeCommandSource, line: String): CommandResult {
        source.clear()
        server.sent.clear()
        val where = source.player?.let { Where.on(it) } ?: Where.Global
        val result = platform.scheduler.runAs(where) { plugin.dispatcher.dispatch(source, line) }
        plugin.texts.feedback.message(result, source)?.let(source::reply)
        // Two ticks: a teleport's steps hop between the player's thread and the destination's.
        platform.scheduler.tick(2)
        return result
    }

    private fun heard(source: FakeCommandSource): List<String> = source.replies + server.sent[source.player!!].orEmpty()

    private fun reconfigure(edit: String) {
        config = co.xenastudios.core.common.config.Configs.load(XTeleportConfig, edit, co.xenastudios.core.testing.TestYaml).let { (it as Outcome.Ok).value.config }
        platform.scheduler.runAs(Where.Global) { plugin.apply(loaded()) }
    }

    @Test
    fun theDatabaseOpensAndTheApiIsThere() {
        assertTrue(plugin.opened.settled())
        assertTrue(XTeleport.isEnabled())
        assertEquals("2.0.0", XTeleport.api().version)
        run(console, "xteleport")
        assertTrue(console.replies.single().startsWith("» xTeleport 2.0.0 on Fake, with 0 warps"), console.replies.toString())
    }

    @Test
    fun aHomeIsSetAndGoneToAfterTheWarmup() {
        run(steve, "sethome base")
        assertEquals(listOf("» Set home base."), steve.replies)
        server.walk(steve.player!!, Location(world, 40.5, 64.0, 40.5))
        run(steve, "home")
        assertEquals(listOf("» Teleporting in 3s. Don't move."), heard(steve))
        platform.scheduler.advance(1.seconds)
        assertEquals("Teleporting in 2...", server.bars[steve.player!!]?.last())
        platform.scheduler.advance(2.seconds)
        assertEquals(0.5, server.where[steve.player!!]!!.x)
        assertTrue("» Welcome to base." in server.sent[steve.player!!].orEmpty(), server.sent.toString())
        run(steve, "homes")
        assertEquals(listOf("» Your homes (1/3):", " base world"), steve.replies)
    }

    @Test
    fun movingCancelsTheWarmup() {
        run(steve, "sethome")
        server.walk(steve.player!!, Location(world, 40.5, 64.0, 40.5))
        run(steve, "home")
        platform.scheduler.advance(1.seconds)
        server.walk(steve.player!!, Location(world, 42.5, 64.0, 40.5))
        platform.scheduler.advance(3.seconds)
        assertEquals(42.5, server.where[steve.player!!]!!.x)
        assertTrue("» Teleport cancelled: you moved." in server.sent[steve.player!!].orEmpty(), server.sent.toString())
    }

    @Test
    fun damageCancelsTheWarmupAndTheListenerIsOnlyThereMeanwhile() {
        run(steve, "sethome")
        assertNull(server.damage)
        run(steve, "home")
        assertNotNull(server.damage).invoke(steve.player!!)
        assertTrue("» Teleport cancelled: you took damage." in server.sent[steve.player!!].orEmpty())
        assertNull(server.damage)
    }

    @Test
    fun theHomeLimitAndNames() {
        run(steve, "sethome a")
        run(steve, "sethome b")
        run(steve, "sethome c")
        run(steve, "sethome d")
        assertEquals(listOf("» You can have 3 homes. Delete one, or set one again to move it."), steve.replies)
        run(steve, "sethome a")
        assertEquals(listOf("» Moved home a here."), steve.replies)
        steve.permissions += "xteleport.homes.5"
        plugin.homeLimits.clear()
        run(steve, "sethome d")
        assertEquals(listOf("» Set home d."), steve.replies)
        run(steve, "sethome bed")
        assertEquals(listOf("» bed is your bed's name. Pick another."), steve.replies)
        run(steve, "sethome no/way")
        assertEquals(listOf("» A home's name is 1 to 32 letters, digits, - or _."), steve.replies)
        run(steve, "delhome A")
        assertEquals(listOf("» Deleted home a."), steve.replies)
        run(steve, "home a")
        assertEquals(listOf("» You have no home called a. See /homes."), steve.replies)
    }

    @Test
    fun anUnsafeHomeIsRefused() {
        run(steve, "sethome base")
        server.ground["world"] = { _, y, _ -> if (y == 63) BlockKind.HURTS or BlockKind.LIQUID else if (y < 63) BlockKind.SOLID or BlockKind.FULL else BlockKind.AIR }
        steve.permissions += "xteleport.warmup.bypass"
        run(steve, "home")
        assertEquals(listOf("» That spot isn't safe right now (lava, blocks or the void), so you stayed here."), heard(steve))
    }

    @Test
    fun tpaAskedAndAccepted() {
        run(steve, "tpa alex")
        assertEquals(listOf("» Asked Alex if you can teleport to them. It lasts 60s."), steve.replies)
        assertEquals(listOf("» Steve wants to teleport to you. [Accept] [Deny]"), server.sent[alex.player!!].orEmpty().toList())
        run(steve, "tpa alex")
        assertEquals(listOf("» You already asked Alex. Wait for an answer, or take it back with /tpacancel."), steve.replies)
        run(alex, "tpaccept")
        assertEquals(listOf("» Accepted Steve's request."), heard(alex))
        platform.scheduler.advance(3.seconds)
        assertEquals(100.5, server.where[steve.player!!]!!.x)
        assertTrue("» Teleported to Alex." in server.sent[steve.player!!].orEmpty())
        run(alex, "tpaccept")
        assertEquals(listOf("» You have no teleport requests."), alex.replies)
    }

    @Test
    fun tpaHereDeniedToggledAndHidden() {
        run(steve, "tpahere alex")
        run(alex, "tpdeny steve")
        assertEquals(listOf("» Denied Steve's request."), heard(alex))
        run(alex, "tptoggle")
        assertEquals(listOf("» You won't get teleport requests now. /tptoggle turns them back on."), alex.replies)
        run(steve, "tpa alex")
        assertEquals(listOf("» Alex isn't taking teleport requests."), steve.replies)
        run(alex, "tptoggle")
        val hook = VisibilityHook { viewer, target -> viewer == steve.player && target == alex.player }
        plugin.registerVisibility("xVanish", hook)
        run(steve, "tpa alex")
        assertEquals(listOf("» No player called alex is online."), steve.replies)
        plugin.unregisterAll("xVanish")
        run(steve, "tpa alex")
        run(steve, "tpacancel")
        assertEquals(listOf("» Took back 1 request(s)."), steve.replies)
        run(steve, "tpa steve")
        assertEquals(listOf("» You can't ask yourself."), steve.replies)
    }

    @Test
    fun warpsAndTheSpawn() {
        run(steve, "setwarp mine")
        assertEquals(listOf("» You don't have permission to do that."), steve.replies)
        steve.everything = true
        run(steve, "setwarp mine")
        assertEquals(listOf("» Set warp mine."), steve.replies)
        run(console, "warps")
        assertEquals(listOf("» Warps (1):", " mine world"), console.replies)
        server.walk(steve.player!!, Location(world, 10.5, 64.0, 10.5))
        run(steve, "warp mine")
        platform.scheduler.advance(0.seconds)
        assertEquals(0.5, server.where[steve.player!!]!!.x)
        run(steve, "setspawn")
        assertEquals(listOf("» Set the spawn here."), steve.replies)
        server.walk(alex.player!!, Location(world, 10.5, 64.0, 10.5))
        run(alex, "spawn")
        platform.scheduler.advance(3.seconds)
        assertEquals(0.5, server.where[alex.player!!]!!.x)
        assertEquals(0.5, server.spawns?.spawnOnJoin(alex.player!!, true)?.x)
        run(console, "delwarp MINE")
        assertEquals(listOf("» Deleted warp mine."), console.replies)
    }

    @Test
    fun rtpFindsASafeSpotOffTheThreadAndLimitsSearches() {
        steve.permissions += "xteleport.warmup.bypass"
        run(steve, "rtp")
        assertEquals("» Looking for a safe spot...", steve.replies.first())
        platform.scheduler.advance(1.seconds)
        val at = server.where[steve.player!!]!!
        assertEquals(64.0, at.y)
        val distance = kotlin.math.sqrt(at.x * at.x + at.z * at.z)
        assertTrue(distance in 290.0..5010.0, "landed $distance blocks out")
        assertTrue(server.sent[steve.player!!].orEmpty().any { it.startsWith("» Teleported to ") }, server.sent.toString())
        run(steve, "rtp")
        assertTrue(heard(steve).single().startsWith("» You can do that again in 4 minutes"), heard(steve).toString())
        run(alex, "rtp nowhere")
        assertEquals(listOf("» /rtp can't send you to nowhere."), alex.replies)
        run(console, "xteleport rtpcheck world_nether 2")
        platform.scheduler.advance(1.seconds)
        assertTrue(console.replies.single().startsWith("» Found a safe spot in 2 of 2 searches in world_nether"), console.replies.toString())
        assertEquals(0, plugin.rtp.running)
    }

    @Test
    fun rtpOverWaterIsNeverFound() {
        server.ground["world"] = { _, y, _ -> if (y < 64) BlockKind.WATER or BlockKind.LIQUID else BlockKind.AIR }
        run(console, "xteleport rtpcheck world")
        platform.scheduler.advance(2.seconds)
        assertEquals(listOf("» No safe spot in world in 1 searches of 10 tries. Check its radii and blocked biomes."), console.replies)
    }

    @Test
    fun costsArePaidAfterTheWarmupAndRefundedOnFailure() {
        reconfigure("commands:\n  spawn:\n    cost: 5\n")
        val economy = FakeEconomy().also { server.economy = it }
        run(steve, "spawn")
        assertEquals(listOf("» That costs $5, which you don't have."), heard(steve))
        economy.balances[steve.player!!] = 12.0
        run(steve, "spawn")
        platform.scheduler.advance(3.seconds)
        assertEquals(7.0, economy.balances[steve.player!!])
        assertTrue("» Paid $5." in server.sent[steve.player!!].orEmpty())
        server.refuseTeleports = true
        run(steve, "spawn")
        platform.scheduler.advance(3.seconds)
        assertEquals(7.0, economy.balances[steve.player!!])
        assertTrue("» The teleport didn't happen, so you got back $5." in server.sent[steve.player!!].orEmpty(), server.sent.toString())
    }

    @Test
    fun theApiTeleportsByItsOptionsAndHearsItsEvents() {
        val to = Location(world, 30.5, 64.0, 30.5)
        val immediate = plugin.teleport(steve.player!!, to, TeleportOptions.immediate())
        platform.scheduler.tick(3)
        assertEquals(TeleportResult.TELEPORTED, immediate.settled())
        val blocker = plugin.events.subscribe(PreTeleportEvent.TYPE, EventListener { it.isCancelled = true })
        val blocked = plugin.teleport(alex.player!!, to, TeleportOptions.immediate())
        platform.scheduler.tick(3)
        assertEquals(TeleportResult.BLOCKED, blocked.settled())
        blocker.cancel()
        plugin.registerDestination("xLimbo", "back", DestinationProvider { pendingOf(Location(world, -20.5, 64.0, 5.5)) })
        val back = plugin.teleport(alex.player!!, "back", TeleportOptions.immediate())
        platform.scheduler.tick(3)
        assertEquals(TeleportResult.TELEPORTED, back.settled())
        assertEquals(-20.5, server.where[alex.player!!]!!.x)
        assertEquals(TeleportResult.FAILED, plugin.teleport(alex.player!!, "nowhere", TeleportOptions.immediate()).settled())
        val cooled = plugin.teleport(steve.player!!, to, TeleportOptions.immediate().withCooldown("arena", 60))
        platform.scheduler.tick(3)
        assertEquals(TeleportResult.TELEPORTED, cooled.settled())
        assertTrue(plugin.cooldownMillis(steve.player!!, "arena") > 59_000)
        assertEquals(TeleportResult.ON_COOLDOWN, plugin.teleport(steve.player!!, to, TeleportOptions.immediate().withCooldown("arena", 60)).also { platform.scheduler.tick(3) }.settled())
    }

    @Test
    fun plotHomesFromAProviderAreListedAndGoneTo() {
        plugin.registerHomes("xPlots") { player -> listOf(co.xenastudios.xteleport.api.Home(player, "plot-1", Location(world, 64.5, 64.0, 64.5))) }
        run(steve, "homes")
        assertEquals(listOf("» Your homes (0/3):", " plot-1 world"), steve.replies)
        steve.permissions += "xteleport.warmup.bypass"
        run(steve, "home plot-1")
        assertEquals(64.5, server.where[steve.player!!]!!.x)
    }

    @Test
    fun offlineStaffAndTheConsoleAreAnsweredInEnglish() {
        run(console, "home")
        assertEquals(listOf("» Only players can use this command."), console.replies)
        run(steve, "homes alex")
        assertEquals(listOf("» You don't have permission to do that."), steve.replies)
        steve.permissions += "xteleport.homes.others"
        run(steve, "homes alex")
        assertEquals(listOf("» Alex has no homes."), steve.replies)
    }

    @Test
    fun forgetDeletesAPlayersHomes() {
        run(steve, "sethome base")
        assertEquals(1, plugin.cachedHomes(steve.player!!)?.size)
        assertEquals(listOf("homes"), (plugin.admin.permissions.permissions.map { it.node }.filter { "forget" in it }).map { "homes" })
    }

    @Test
    fun aPlayerHiddenByTheServerIsNotCompleted() {
        server.hiddenFrom[steve.player!!] = mutableSetOf(alex.player!!)
        run(steve, "tpa alex")
        assertEquals(listOf("» No player called alex is online."), steve.replies)
    }

    @Test
    fun placeholders() {
        run(steve, "sethome base")
        assertEquals("1", plugin.placeholders.resolve("homes", steve.player!!))
        assertEquals("3", plugin.placeholders.resolve("homes_limit", steve.player!!))
        assertEquals("0", plugin.placeholders.resolve("warps", null))
        assertEquals("0", plugin.placeholders.resolve("cooldown_rtp", steve.player!!))
        assertNull(plugin.placeholders.resolve("nonsense", steve.player!!))
    }

    private val PlayerRef.name: String get() = toString()
}
