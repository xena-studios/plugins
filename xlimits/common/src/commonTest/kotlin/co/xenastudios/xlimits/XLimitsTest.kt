package co.xenastudios.xlimits

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.event.EventListener
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandResult
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.Configs
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.world.BlockChange
import co.xenastudios.core.common.world.BlockData
import co.xenastudios.core.common.world.ChangeCause
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.core.testing.TestYaml
import co.xenastudios.xlimits.FakeLimitsServer.Companion.BOAT
import co.xenastudios.xlimits.FakeLimitsServer.Companion.COW
import co.xenastudios.xlimits.FakeLimitsServer.Companion.ITEM
import co.xenastudios.xlimits.FakeLimitsServer.Companion.PIG
import co.xenastudios.xlimits.FakeLimitsServer.Companion.TNT
import co.xenastudios.xlimits.FakeLimitsServer.Companion.VILLAGER
import co.xenastudios.xlimits.FakeLimitsServer.Companion.WOLF
import co.xenastudios.xlimits.FakeLimitsServer.Companion.ZOMBIE
import co.xenastudios.xlimits.api.ChunkRef
import co.xenastudios.xlimits.api.LimitKind
import co.xenastudios.xlimits.api.SpawnRoute
import co.xenastudios.xlimits.api.XLimits
import co.xenastudios.xlimits.api.event.FarmRestoredEvent
import co.xenastudios.xlimits.api.event.FarmSlowedEvent
import co.xenastudios.xlimits.api.event.LimitReachedEvent
import co.xenastudios.xlimits.api.event.ProfileChangedEvent
import co.xenastudios.xlimits.config.XLimitsConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** xLimits on the fake platform: Folia's regions and virtual time, every check on its chunk's thread. */
class XLimitsTest {
    private val platform = FakePlatform()
    private val server = FakeLimitsServer(platform.scheduler)
    private var config: Config = XLimitsConfig.defaults
    private val plugin = XLimitsPlugin(platform, server, { loaded() }, server, server)
    private val steve = FakeCommandSource.player("Steve")
    private val staff = FakeCommandSource.player("Staff", "xlimits.alerts", "xlimits.here", "xlimits.top", "xlimits.restore", "xlimits.teleport")
    private val console = FakeCommandSource.console()
    private val home = server.chunk(0, 0)

    private fun loaded() = Loaded(config, Translations.of(XLimitsMessages.catalog, emptyMap()))

    private fun read(yaml: String): Config = when (val result = Configs.load(XLimitsConfig, yaml, TestYaml)) {
        is Outcome.Ok -> result.value.config.also { assertTrue(result.value.problems.isEmpty(), result.value.problems.toString()) }
        is Outcome.Err -> throw AssertionError(result.error.toString())
    }

    // What a source was told, without the theme's prefix.
    private val FakeCommandSource.said: List<String> get() = replies.map { it.removePrefix("» ") }

    init {
        platform.scheduler.runAs(Where.Global) { plugin.start(loaded()) }
        server.join(steve)
        server.join(staff, server.at(5, 5))
        server.loaded[home] = ArrayList()
    }

    @AfterTest
    fun stop() {
        platform.scheduler.runAs(Where.Global) { plugin.stop() }
    }

    private fun reconfigure(yaml: String) {
        config = read(yaml)
        platform.scheduler.runAs(Where.Global) { plugin.apply(loaded()) }
    }

    private fun <T> at(chunk: ChunkRef, block: () -> T): T = platform.scheduler.runAs(Where.At(chunk.center()), block)

    private fun spawn(kind: EntityKind, route: SpawnRoute = SpawnRoute.NATURAL, chunk: ChunkRef = home, by: FakeCommandSource? = null): Boolean = at(chunk) {
        plugin.spawning(kind, chunk.center(), route, by?.player).also { if (it) server.put(chunk, kind) }
    }

    private fun run(source: FakeCommandSource, line: String, where: Where = Where.Global): CommandResult {
        val result = platform.scheduler.runAs(where) { plugin.dispatcher.dispatch(source, line) }
        plugin.texts.feedback.message(result, source)?.let(source::reply)
        platform.scheduler.tick(2)
        return result
    }

    @Test
    fun aCapRefusesNewMobsAndRemovesNone() {
        reconfigure("entities:\n  limits:\n    animals: 5\n")
        server.put(home, COW, 4)
        assertTrue(spawn(PIG))
        assertFalse(spawn(COW, SpawnRoute.BREEDING, by = steve))
        assertEquals(5, server.mobs(home).size)
        assertEquals(listOf("This chunk has 5 of 5 animals. No more can be added here."), steve.said)
        // Another chunk has its own count.
        server.loaded[server.chunk(1, 0)] = ArrayList()
        assertTrue(spawn(COW, chunk = server.chunk(1, 0)))
        // Monsters aren't animals.
        assertTrue(spawn(ZOMBIE))
    }

    @Test
    fun aChunkIsCountedAtMostOnceASecondAndAllowedSpawnsAreAddedMeanwhile() {
        reconfigure("entities:\n  limits:\n    cow: 3\n")
        val before = server.censuses
        assertTrue(spawn(COW, SpawnRoute.SPAWNER))
        assertTrue(spawn(COW, SpawnRoute.SPAWNER))
        assertTrue(spawn(COW, SpawnRoute.SPAWNER))
        assertFalse(spawn(COW, SpawnRoute.SPAWNER))
        assertFalse(spawn(COW, SpawnRoute.SPAWNER))
        assertEquals(1, server.censuses - before, "one count for five attempts")
        // Two cows die; a second later the chunk is counted again.
        repeat(2) { server.loaded[home]!!.removeAt(0) }
        platform.scheduler.advance(1.seconds)
        assertTrue(spawn(COW, SpawnRoute.SPAWNER))
        assertEquals(2, server.censuses - before)
    }

    @Test
    fun aRouteThatIsntListedIsNeverRefusedButStillCounted() {
        reconfigure("entities:\n  limits:\n    animals: 1\n  routes: [spawn-egg]\n")
        assertTrue(spawn(COW, SpawnRoute.NATURAL))
        assertTrue(spawn(COW, SpawnRoute.BREEDING))
        assertFalse(spawn(COW, SpawnRoute.SPAWN_EGG, by = steve))
    }

    @Test
    fun theBypassPermissionAndTheEventLetAPlayerPast() {
        reconfigure("entities:\n  limits:\n    vehicles: 1\n")
        assertTrue(spawn(BOAT, SpawnRoute.PLACED, by = steve))
        steve.permissions += XLimits.BYPASS_PERMISSION
        assertTrue(spawn(BOAT, SpawnRoute.PLACED, by = steve))
        steve.permissions -= XLimits.BYPASS_PERMISSION
        var heard: LimitReachedEvent? = null
        val sub = plugin.events.subscribe(LimitReachedEvent.TYPE, EventListener { heard = it; it.isCancelled = true })
        assertTrue(spawn(BOAT, SpawnRoute.PLACED, by = steve))
        assertEquals(LimitKind.ENTITY, heard?.kind)
        assertEquals("vehicles", heard?.group)
        assertEquals(SpawnRoute.PLACED, heard?.route)
        sub.cancel()
        assertFalse(spawn(BOAT, SpawnRoute.PLACED, by = steve))
        assertTrue(steve.said.isNotEmpty())
    }

    @Test
    fun ownGroupsAndWorldOverridesAndExemptWorlds() {
        reconfigure(
            """
            entities:
              limits:
                farm: 2
              groups:
                farm: [cow, pig]
            worlds:
              overrides:
                world:
                  farm: 3
            """.trimIndent(),
        )
        assertTrue(spawn(COW))
        assertTrue(spawn(PIG))
        assertTrue(spawn(COW))
        assertFalse(spawn(PIG))
        assertTrue(spawn(ZOMBIE))
        reconfigure("worlds:\n  exempt: [world]\nentities:\n  limits:\n    animals: 0\n")
        assertTrue(spawn(COW))
    }

    @Test
    fun staffAreAlertedOncePerChunkPerCooldown() {
        reconfigure("entities:\n  limits:\n    animals: 0\n")
        server.plugins = setOf("xRewind")
        assertFalse(spawn(COW))
        assertFalse(spawn(COW))
        val alerts = staff.said.filter { "reached" in it }
        assertEquals(1, alerts.size, staff.said.toString())
        assertTrue("animals reached at world 8, 8 (0/0). [Teleport] [Who built this]" in alerts[0], alerts[0])
        assertTrue(server.consoleLines.any { "animals reached" in it })
        assertTrue(steve.said.none { "reached" in it })
        platform.scheduler.advance(5.minutes)
        assertFalse(spawn(COW))
        assertEquals(2, staff.said.count { "reached" in it })
    }

    @Test
    fun blocksAreCountedOnceThenKeptFromPlacingAndBreaking() {
        reconfigure("blocks:\n  limits:\n    hopper: 3\n")
        server.blocks[home] = mutableMapOf("hopper" to 2, "stone" to 100)
        val at = home.center()
        // Bukkit puts the block there before asking: the count has it, so it's counted once.
        server.blocks[home]!!["hopper"] = 3
        assertTrue(at(home) { plugin.placing("hopper", at, steve.player) })
        assertEquals(1, server.blockCounts)
        assertFalse(at(home) { plugin.placing("hopper", at, steve.player) })
        assertEquals(listOf("This chunk has 3 of 3 hopper. Place it in another chunk."), steve.said)
        at(home) { plugin.removed("hopper", at) }
        assertTrue(at(home) { plugin.placing("hopper", at, steve.player) })
        assertEquals(1, server.blockCounts, "kept up to date, not counted again")
        // A block no limit names costs one map read.
        assertTrue(at(home) { plugin.placing("stone", at, steve.player) })
        assertEquals(3, at(home) { plugin.count(home, "hopper") })
    }

    @Test
    fun aBlockPlacedOverSnowOrGrassIsCountedAsPlaced() {
        reconfigure("blocks:\n  limits:\n    hopper: 1\n")
        server.blocks[home] = mutableMapOf("hopper" to 0)
        val at = home.center()
        class Data(override val type: String) : BlockData() {
            override val data: String get() = type
            override val isAir: Boolean get() = type == "minecraft:air"
            override val native: Any get() = type
        }
        fun change(before: String, after: String) = BlockChange(
            at.world, at.blockX, at.blockY, at.blockZ, Data(before), Data(after), ChangeCause.PLAYER, steve.player, "Steve", null, true, Unit,
        )
        // The core's isPlace is for air only; a snow layer or grass the hopper replaces is a place too.
        val overSnow = change("minecraft:snow", "minecraft:hopper")
        at(home) { BlockChanges.on(plugin, overSnow, at, placing = true) }
        assertFalse(overSnow.isCancelled)
        val overGrass = change("minecraft:short_grass", "minecraft:hopper")
        at(home) { BlockChanges.on(plugin, overGrass, at, placing = true) }
        assertTrue(overGrass.isCancelled, "the second hopper is past the cap")
        assertEquals(listOf("This chunk has 1 of 1 hopper. Place it in another chunk."), steve.said)
        // Breaking it counts it off; a change another plugin cancelled first counts nothing.
        at(home) { BlockChanges.on(plugin, change("minecraft:hopper", "minecraft:air"), at, placing = true) }
        val cancelled = change("minecraft:air", "minecraft:hopper").also { it.isCancelled = true }
        at(home) { BlockChanges.on(plugin, cancelled, at, placing = true) }
        assertEquals(0, at(home) { plugin.count(home, "hopper") })
    }

    @Test
    fun placingWhileTheChunkIsCountedOffThreadIsRemembered() {
        reconfigure("blocks:\n  limits:\n    redstone: 2\n  groups:\n    redstone: [repeater, comparator]\n")
        server.asyncCounts = true
        server.blocks[home] = mutableMapOf("repeater" to 1)
        val at = home.center()
        assertTrue(at(home) { plugin.placing("repeater", at, null) })
        assertTrue(at(home) { plugin.placing("comparator", at, null) })
        server.finishCounts()
        // 1 counted (the first, already in the world, left out and then counted once) + 1 placed meanwhile.
        assertFalse(at(home) { plugin.placing("comparator", at, null) })
    }

    @Test
    fun aCrammedFarmIsSlowedThenRestoredAsItEmpties() {
        reconfigure("farms:\n  rules:\n    animals:\n      threshold: 10\n      actions: [no-collisions, no-ai]\nentities:\n  enabled: false\n")
        var slowedEvents = 0
        var restoredEvents = 0
        plugin.events.subscribe(FarmSlowedEvent.TYPE, EventListener { slowedEvents++ })
        plugin.events.subscribe(FarmRestoredEvent.TYPE, EventListener { restoredEvents += it.restored })
        val cows = server.put(home, COW, 11)
        cows[0].named = true
        server.put(home, VILLAGER, 2)
        assertTrue(spawn(COW, SpawnRoute.BREEDING))
        assertEquals(10, server.mobs(home).count { FakeLimitsServer.SLOWED in it.tags }, "named and villagers left alone")
        assertEquals(1, slowedEvents)
        assertTrue(server.mobs(home).first { FakeLimitsServer.SLOWED in it.tags }.tags.containsAll(listOf("NO_COLLISIONS", "NO_AI")))
        // Under the threshold, but not by enough: still slowed.
        repeat(2) { server.loaded[home]!!.remove(server.loaded[home]!!.last { it.kind == COW }) }
        platform.scheduler.advance(1.seconds)
        assertTrue(spawn(PIG, SpawnRoute.BREEDING))
        assertTrue(server.mobs(home).any { FakeLimitsServer.SLOWED in it.tags })
        // Well under: back to normal.
        repeat(5) { server.loaded[home]!!.remove(server.loaded[home]!!.last { it.kind == COW }) }
        platform.scheduler.advance(1.seconds)
        assertTrue(spawn(PIG, SpawnRoute.BREEDING))
        assertTrue(server.mobs(home).none { FakeLimitsServer.SLOWED in it.tags })
        assertTrue(restoredEvents > 0)
    }

    @Test
    fun aSlowedFarmFromBeforeARestartIsRestoredAsItsChunkLoads() {
        reconfigure("farms:\n  rules:\n    animals:\n      threshold: 10\n      actions: [no-collisions]\n")
        server.put(home, COW, 3).forEach { it.tags += FakeLimitsServer.SLOWED }
        at(home) { plugin.chunkLoaded(home) }
        assertTrue(server.mobs(home).none { FakeLimitsServer.SLOWED in it.tags })
    }

    @Test
    fun cancellingFarmSlowedLeavesTheFarmAlone() {
        reconfigure("farms:\n  rules:\n    cow:\n      threshold: 2\n      actions: [no-breeding]\n")
        plugin.events.subscribe(FarmSlowedEvent.TYPE, EventListener { it.isCancelled = true })
        server.put(home, COW, 5)
        at(home) { plugin.chunkLoaded(home) }
        assertTrue(server.mobs(home).none { FakeLimitsServer.SLOWED in it.tags })
    }

    @Test
    fun restorePutsEveryMobBackAndPausesSlowing() {
        reconfigure("farms:\n  rules:\n    animals:\n      threshold: 2\n      actions: [no-collisions]\n")
        server.put(home, COW, 5)
        at(home) { plugin.chunkLoaded(home) }
        assertEquals(5, server.mobs(home).count { FakeLimitsServer.SLOWED in it.tags })
        // It asks to be typed again, then runs.
        run(staff, "xlimits restore")
        run(staff, "xlimits restore")
        assertTrue(staff.said.any { "Restored 5 slowed mobs." in it }, staff.said.toString())
        platform.scheduler.advance(1.seconds)
        at(home) { plugin.chunkLoaded(home) }
        assertTrue(server.mobs(home).none { FakeLimitsServer.SLOWED in it.tags }, "paused until a reload")
    }

    @Test
    fun aRedstoneClockIsBrokenAndStaffAreTold() {
        reconfigure("redstone:\n  enabled: true\n  changes: 5\n  window: 2s\n")
        val at = Location(server.world, 3.0, 70.0, 3.0)
        repeat(5) { assertTrue(at(home) { plugin.redstone(at) }) }
        assertFalse(at(home) { plugin.redstone(at) })
        assertEquals(listOf(at), server.broken)
        assertTrue(staff.said.any { "redstone clock at world 3 70 3 changed 6 times" in it }, staff.said.toString())
        // A slow one is never stopped.
        val slow = Location(server.world, 4.0, 70.0, 4.0)
        repeat(20) {
            assertTrue(at(home) { plugin.redstone(slow) })
            platform.scheduler.advance(500.milliseconds)
        }
    }

    @Test
    fun tntAndFallingBlocksAreCappedPerChunk() {
        reconfigure("tnt:\n  enabled: true\n  primed: 2\n  falling: 1\n")
        server.put(home, TNT, 1)
        assertTrue(at(home) { plugin.priming(home.center(), steve.player) })
        assertFalse(at(home) { plugin.priming(home.center(), steve.player) })
        assertEquals(listOf("This chunk already has 2 of 2 primed TNT."), steve.said)
        assertTrue(at(home) { plugin.falling(home.center()) })
        assertFalse(at(home) { plugin.falling(home.center()) })
    }

    @Test
    fun portalsLetSoManyThroughPerWindow() {
        reconfigure("portals:\n  enabled: true\n  entities: 3\n  window: 5s\n")
        repeat(3) { assertTrue(at(home) { plugin.portal(ITEM, home.center()) }) }
        assertFalse(at(home) { plugin.portal(ITEM, home.center()) })
        platform.scheduler.advance(5.seconds)
        assertTrue(at(home) { plugin.portal(ITEM, home.center()) })
    }

    @Test
    fun petsAreCappedPerOwnerAndPerType() {
        reconfigure("pets:\n  enabled: true\n  limit: 4\n  per-type:\n    wolf: 1\n")
        val owner = steve.player!!
        // Two pets seen as their chunk loads.
        server.put(home, PIG, 2, owner)
        at(home) { plugin.chunkLoaded(home) }
        assertTrue(at(home) { plugin.taming(owner, WOLF, home.center(), "w1") })
        assertFalse(at(home) { plugin.taming(owner, WOLF, home.center(), "w2") })
        assertEquals("You have 1 of 1 pets of this kind. Pets you have are kept.", steve.said.last())
        assertTrue(at(home) { plugin.taming(owner, COW, home.center(), "c1") })
        assertFalse(at(home) { plugin.taming(owner, COW, home.center(), "c2") })
        assertEquals("You have 4 of 4 pets. Pets you have are kept.", steve.said.last())
        plugin.petGone("w1")
        assertTrue(at(home) { plugin.taming(owner, WOLF, home.center(), "w3") })
    }

    @Test
    fun aLoadProfileTightensWithHysteresisAndAMinimumTime() {
        reconfigure(
            """
            entities:
              limits:
                animals: 10
            profiles:
              enabled: true
              minimum-time: 10s
              list:
                busy:
                  when: players-online-above
                  on: 2
                  off: 1
                  scale: 0.5
                strained:
                  when: tps-below
                  on: 15
                  off: 18
                  limits:
                    animals: 1
            """.trimIndent(),
        )
        val changes = ArrayList<String>()
        plugin.events.subscribe(ProfileChangedEvent.TYPE, EventListener { changes += "${it.from}>${it.to}" })
        assertEquals(10, at(home) { plugin.limit(home, "animals") })
        server.join(FakeCommandSource.player("Alex"))
        platform.scheduler.advance(1.seconds)
        assertEquals(5, at(home) { plugin.limit(home, "animals") })
        assertEquals("busy", plugin.activeProfile(home))
        assertTrue(staff.said.any { "Load profile busy is on in the region at world 0, 0: 3 players online, over 2." in it }, staff.said.toString())
        // Back under its on, but not at its off: stays.
        server.players.remove(server.players.keys.last())
        platform.scheduler.advance(1.seconds)
        assertEquals(5, at(home) { plugin.limit(home, "animals") })
        // TPS falls: the stricter one wins at once.
        server.tps = 12.0
        platform.scheduler.advance(1.seconds)
        assertEquals(1, at(home) { plugin.limit(home, "animals") })
        // TPS is back, but only after the minimum time does it turn off.
        server.tps = 19.5
        platform.scheduler.advance(1.seconds)
        assertEquals(1, at(home) { plugin.limit(home, "animals") })
        platform.scheduler.advance(10.seconds)
        assertEquals(5, at(home) { plugin.limit(home, "animals") }, "busy still holds: 2 players is over its off")
        assertEquals(listOf("normal>busy", "busy>strained", "strained>busy"), changes)
    }

    @Test
    fun aProviderChangesOrExemptsAnArea() {
        reconfigure("entities:\n  limits:\n    animals: 1\n")
        val sub = plugin.register { location, group, configured -> if (location.chunkX == 0 && group == "animals") XLimits.NO_LIMIT else configured }
        assertTrue(spawn(COW))
        assertTrue(spawn(COW))
        assertEquals(XLimits.NO_LIMIT, at(home) { plugin.limit(home, "animals") })
        sub.cancel()
        assertFalse(spawn(COW))
    }

    @Test
    fun hereShowsTheChunkAgainstEachLimit() {
        reconfigure("entities:\n  limits:\n    animals: 4\nblocks:\n  limits:\n    hopper: 10\n")
        server.where[steve.player!!] = home.center()
        val me = FakeCommandSource.player("Steve", "xlimits.here")
        server.put(home, COW, 4)
        server.blocks[home] = mutableMapOf("hopper" to 7)
        run(me, "xlimits here", Where.on(me.player!!))
        assertEquals(
            listOf(
                "Chunk world 0, 0, profile normal:",
                " • animals: 4 of 4",
                " • hopper: 7 of 10",
                "The server's own settings here: max-entity-collisions 8",
            ),
            me.said,
        )
    }

    @Test
    fun topListsTheBusiestChunks() {
        server.put(home, COW, 3)
        server.put(server.chunk(2, 2), ZOMBIE, 9)
        run(staff, "xlimits top")
        assertEquals("The busiest chunks by entities:", staff.said[1])
        assertTrue(staff.said[2].startsWith(" 1. world 2, 2: 9 entities"), staff.said.toString())
        staff.clear()
        run(staff, "xlimits near Steve")
        assertTrue(staff.said.any { "around Steve" in it })
        run(steve, "xlimits top")
        assertTrue(steve.said.last().contains("permission"), steve.said.toString())
    }

    @Test
    fun theDefaultsBootWithNothingButEntityFarmAndBlockListeners() {
        reconfigure("")
        assertEquals(setOf(Switch.ENTITIES, Switch.CHUNKS, Switch.BLOCKS), server.listening)
        reconfigure("entities:\n  enabled: false\nfarms:\n  enabled: false\nblocks:\n  enabled: false\n")
        assertTrue(server.listening.isEmpty(), "nothing on, nothing listening")
        run(console, "xlimits")
        assertTrue(console.said.last().startsWith("xLimits 1.0.0 on Fake: 0 limits on."), console.said.toString())
    }

    @Test
    fun actionsTheServerCantDoAreLeftOutWithAWarning() {
        val bare = FakeLimitsServer(platform.scheduler, actions = setOf(co.xenastudios.xlimits.api.SlowAction.NO_COLLISIONS))
        val other = XLimitsPlugin(platform, bare, { loaded() }, bare, bare)
        config = read("farms:\n  rules:\n    animals:\n      threshold: 3\n      actions: [no-ai]\n")
        val problems = platform.scheduler.runAs(Where.Global) { other.apply(loaded()) }
        assertTrue(problems.any { "no-ai can't be done on this server" in it }, problems.toString())
        assertTrue(other.rules.farms.isEmpty())
    }

    @Test
    fun theConfigWritesBackAsItReads() {
        val text = Configs.defaultText(XLimitsConfig)
        val back = (Configs.load(XLimitsConfig, text, TestYaml) as Outcome.Ok).value
        assertTrue(back.problems.isEmpty(), back.problems.toString())
        assertEquals(XLimitsConfig.defaults[XLimitsConfig.profiles.list], back.config[XLimitsConfig.profiles.list])
        assertEquals(XLimitsConfig.defaults[XLimitsConfig.farms.rules], back.config[XLimitsConfig.farms.rules])
        assertNotNull(back.config[XLimitsConfig.entities.limits]["animals"])
    }
}
