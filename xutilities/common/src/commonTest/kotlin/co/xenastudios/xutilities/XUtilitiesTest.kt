package co.xenastudios.xutilities

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandResult
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.Configs
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.permission.PermissionDefault
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.core.testing.TestYaml
import co.xenastudios.xutilities.api.XUtilities
import co.xenastudios.xutilities.api.event.BackRecordEvent
import co.xenastudios.xutilities.api.event.BackTeleportEvent
import co.xenastudios.xutilities.config.XUtilitiesConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** xUtilities' commands, run as its adapters run them: typed by fake players on the fake platform. */
class XUtilitiesTest {

    private val platform = FakePlatform()
    private val server = FakeServer()
    private var configText: String? = null
    private val admin = FakeCommandSource.player("Admin").apply { everything = true }
    private val steve = FakeCommandSource.player("Steve")
    private val console = FakeCommandSource.console()
    private val adminPlayer = server.add(admin)
    private val stevePlayer = server.add(steve, Location(FakeServer.WORLD, 10.5, 64.0, 10.5))

    private fun config(): Config = configText?.let { (Configs.load(XUtilitiesConfig, it, TestYaml) as Outcome.Ok).value.config } ?: XUtilitiesConfig.defaults

    private val plugin = XUtilitiesPlugin(
        platform, server, server,
        files = { Loaded(config(), Translations.of(XUtilitiesMessages.catalog, emptyMap())) },
        languages = server,
    ).also { plugin ->
        for (player in server.players.values) platform.scheduler.spawn(player.ref.uuid, player.location)
        platform.scheduler.runAs(Where.Global) { plugin.start(Loaded(config(), Translations.of(XUtilitiesMessages.catalog, emptyMap()))) }
    }

    @AfterTest
    fun stop() {
        platform.scheduler.runAs(Where.Global) { plugin.stop() }
    }

    /** Runs [line] as [source], shows them the result as an adapter does, and lets the tasks it queued run. */
    private fun run(source: FakeCommandSource, line: String): CommandResult {
        val result = plugin.dispatcher.dispatch(source, line)
        plugin.texts.feedback.message(result, source)?.let(source::reply)
        platform.scheduler.tick()
        return result
    }

    private fun FakePlayer.last(): String = (messages + source.replies).last()

    private fun reload(text: String) {
        configText = text
        platform.scheduler.runAs(Where.Global) { run(console, "xutilities reload") }
        platform.scheduler.tick()
    }

    @Test
    fun flyTogglesAndTellsBothPlayers() {
        run(admin, "fly")
        assertTrue(adminPlayer.allowFlight)
        assertEquals("» Flight enabled.", adminPlayer.messages.last())
        run(admin, "fly steve")
        assertTrue(stevePlayer.allowFlight)
        assertEquals("» Flight enabled.", stevePlayer.messages.last())
        assertEquals("» Flight enabled for Steve.", admin.replies.last())
        run(admin, "flight")
        assertFalse(adminPlayer.allowFlight)
        assertEquals("» Flight disabled.", adminPlayer.messages.last())
    }

    @Test
    fun commandsDefaultToOpsAndOthersNeedTheirOwnPermission() {
        assertEquals(CommandResult.NoPermission("xutilities.command.fly"), run(steve, "fly"))
        assertEquals("» You don't have permission to do that.", steve.replies.last())
        steve.permissions += "xutilities.command.heal"
        run(steve, "heal admin")
        assertEquals("» You don't have permission to do that.", steve.replies.last())
        run(steve, "heal")
        assertEquals("» You've been healed.", stevePlayer.messages.last())
        assertEquals(20.0, stevePlayer.health)
        assertEquals(20, stevePlayer.food)
        assertEquals(0, stevePlayer.fire)
        assertFalse(stevePlayer.harmful)
    }

    @Test
    fun pingAndLinkCommandsAreEveryones() {
        val nodes = plugin.permissions.permissions.associateBy { it.node }
        assertEquals(PermissionDefault.EVERYONE, nodes.getValue("xutilities.command.ping").default)
        assertEquals(PermissionDefault.EVERYONE, nodes.getValue("xutilities.command.discord").default)
        assertEquals(PermissionDefault.OP, nodes.getValue("xutilities.command.fly.others").default)
        steve.permissions += listOf("xutilities.command.ping", "xutilities.command.discord")
        run(steve, "ping")
        assertEquals("» Your ping is 42ms.", steve.replies.last())
        run(steve, "discord")
        assertEquals("» Join our Discord: discord.gg/example", steve.replies.last())
        run(console, "discord")
        assertEquals("» Join our Discord: discord.gg/example", console.replies.last())
    }

    @Test
    fun unknownPlayersAndBadArguments() {
        run(admin, "heal Nobody")
        assertEquals("» No player called Nobody is online.", admin.replies.last())
        run(admin, "speed 11")
        assertEquals("» Speed must be from 1 to 10.", admin.replies.last())
        run(admin, "speed fast")
        assertEquals("» Speed must be from 1 to 10.", admin.replies.last())
        run(admin, "setrotation")
        assertEquals("» Usage: /setrotation <yaw> <pitch> [player]", admin.replies.last())
        run(console, "fly")
        assertEquals("» Only players can use this command.", console.replies.last())
        run(console, "top")
        assertEquals("» Only players can use this command.", console.replies.last())
        run(console, "clear Nobody")
        assertEquals("» No player called Nobody is online.", console.replies.last())
    }

    @Test
    fun adminCommandAnswersToXu() {
        run(console, "xu")
        assertTrue(console.replies.last().startsWith("» xUtilities ${XUtilitiesPlugin.VERSION} on Fake, with "), console.replies.last())
        run(console, "xutilities info")
        assertTrue(console.replies.last().startsWith("» xUtilities "))
    }

    @Test
    fun speedSetsWalkOrFlySpeedOnTheOneToTenScale() {
        run(admin, "speed 5")
        assertEquals(0.5f, adminPlayer.walkSpeed)
        assertEquals("» Your walking speed is now 5.", adminPlayer.messages.last())
        adminPlayer.flying = true
        run(admin, "setspeed 2.5 steve")
        assertEquals(0.25f, stevePlayer.walkSpeed)
        assertEquals("» Set Steve's walking speed to 2.5.", admin.replies.last())
        run(admin, "speed 10")
        assertEquals(1f, adminPlayer.flySpeed)
        assertEquals("» Your flying speed is now 10.", adminPlayer.messages.last())
    }

    @Test
    fun setrotationSaysTheAnglesAsTyped() {
        run(admin, "setrotation 12.3 -4.5")
        assertEquals(12.3f, adminPlayer.location.yaw)
        assertEquals(-4.5f, adminPlayer.location.pitch)
        assertEquals("» Now facing yaw 12.3, pitch -4.5.", adminPlayer.messages.last())
        run(admin, "setrotation 400 0")
        assertEquals("» 400 is more than 360. Usage: /setrotation <yaw> <pitch> [player]", admin.replies.last())
    }

    @Test
    fun gamemodeTakesNamesNumbersLettersAndAliases() {
        run(admin, "gamemode creative")
        assertEquals(GameMode.CREATIVE, adminPlayer.mode)
        assertEquals("» Your gamemode is now creative.", adminPlayer.messages.last())
        run(admin, "gm 2 steve")
        assertEquals(GameMode.ADVENTURE, stevePlayer.mode)
        assertEquals("» Set Steve's gamemode to adventure.", admin.replies.last())
        run(admin, "gm sp")
        assertEquals(GameMode.SPECTATOR, adminPlayer.mode)
        run(admin, "gm gms")
        assertEquals(GameMode.SURVIVAL, adminPlayer.mode)
        run(admin, "gmc")
        assertEquals(GameMode.CREATIVE, adminPlayer.mode)
        // /gamemode needs the mode's own permission too.
        steve.permissions += "xutilities.command.gamemode"
        run(steve, "gamemode creative")
        assertEquals("» You don't have permission to do that.", steve.replies.last())
        assertEquals(GameMode.ADVENTURE, stevePlayer.mode)
        assertEquals(listOf("survival", "creative", "adventure", "spectator"), plugin.dispatcher.suggest(admin, "gamemode "))
        assertEquals(emptyList(), plugin.dispatcher.suggest(steve, "gamemode "))
    }

    @Test
    fun healDoesNothingForTheDead() {
        stevePlayer.dead = true
        run(admin, "heal steve")
        assertEquals(5.0, stevePlayer.health)
        assertTrue(stevePlayer.messages.isEmpty())
    }

    @Test
    fun repairAndClear() {
        run(admin, "repair")
        assertEquals("» Your gear has been repaired.", adminPlayer.messages.last())
        run(admin, "fix all")
        assertEquals(0, adminPlayer.damaged)
        run(admin, "repair all")
        assertEquals("» Nothing needs repairing.", admin.replies.last())
        run(admin, "repair steve")
        assertEquals("» Repaired Steve's gear.", admin.replies.last())
        run(admin, "repair all steve")
        assertEquals(0, stevePlayer.damaged)
        run(admin, "ci")
        assertEquals(0, adminPlayer.inventory)
        assertEquals(0, adminPlayer.armor)
    }

    @Test
    fun menusOpenOrSayWhyNot() {
        run(admin, "anvil")
        assertEquals("anvil", adminPlayer.opened)
        run(admin, "wb")
        assertEquals("craft", adminPlayer.opened)
        run(admin, "trash")
        assertEquals("trash:Disposal", adminPlayer.opened)
        assertEquals(listOf("block.barrel.open"), adminPlayer.sounds)
        run(admin, "ec steve")
        assertEquals("enderchest:Steve", adminPlayer.opened)
        assertEquals("» Opened Steve's ender chest.", adminPlayer.messages.last())

        server.features = Features(stations = mapOf(Station.LOOM to "Paper 1.14 or newer"), ping = "Minecraft 1.17 or newer")
        run(admin, "loom")
        assertEquals("» /loom can't open its menu on this server: it needs Paper 1.14 or newer.", admin.replies.last())
        run(admin, "ping")
        assertEquals("» /ping isn't available on this server: it needs Minecraft 1.17 or newer.", admin.replies.last())
    }

    @Test
    fun topFindsTheHighestSafeBlock() {
        run(admin, "top")
        assertEquals(71.0, adminPlayer.location.y)
        assertEquals("» Teleported to the top.", adminPlayer.messages.last())
        server.blocks = mapOf(63 to "stone", 70 to "lava")
        adminPlayer.location = Location(FakeServer.WORLD, 0.5, 64.0, 0.5)
        run(admin, "top")
        assertEquals("» There's no safe block to stand on here.", adminPlayer.messages.last())
        assertEquals(64.0, adminPlayer.location.y)
    }

    @Test
    fun backReturnsToTheLastTeleportAndForgetsOnQuit() {
        run(admin, "back")
        assertEquals("» You have nowhere to go back to.", adminPlayer.messages.last())
        run(admin, "top")
        assertEquals(71.0, adminPlayer.location.y)
        run(admin, "back")
        assertEquals(64.0, adminPlayer.location.y)
        assertEquals("» Teleported back to your previous location.", adminPlayer.messages.last())
        // Dying counts too.
        server.listener!!.died(admin.player!!, Location(FakeServer.WORLD, 5.0, 30.0, 5.0))
        assertEquals(30.0, plugin.getBackLocation(admin.player!!)?.y)
        server.listener!!.quit(admin.player!!)
        assertNull(plugin.getBackLocation(admin.player!!))
    }

    @Test
    fun backHearsItsOwnTeleportsWhereTheServerHasNoTeleportEvent() {
        server.features = Features(teleportEvents = false)
        run(admin, "top")
        assertEquals(64.0, plugin.getBackLocation(admin.player!!)?.y)
    }

    @Test
    fun theApiAndItsEvents() {
        val api = XUtilities.api()
        assertSame(plugin, api)
        assertTrue(api.isBackEnabled())
        val spot = Location(FakeServer.WORLD, 1.0, 2.0, 3.0)
        assertTrue(api.setBackLocation(steve.player!!, spot))
        assertEquals(spot, api.getBackLocation(steve.player!!))
        api.clearBackLocation(steve.player!!)
        assertNull(api.getBackLocation(steve.player!!))
        assertTrue(api.isCommandEnabled("/XU"))
        assertTrue(api.isCommandEnabled("xutilities:fly"))
        assertTrue(api.isCommandEnabled("discord"))
        assertFalse(api.isCommandEnabled("vote"))

        // A cancelled record keeps the spot from before; a cancelled /back stays put.
        val records = mutableListOf<BackRecordEvent>()
        api.events.subscribe(BackRecordEvent.TYPE) { records += it; it.isCancelled = it.reason == BackRecordEvent.Reason.DEATH }
        server.listener!!.died(admin.player!!, spot)
        assertNull(api.getBackLocation(admin.player!!))
        run(admin, "top")
        assertEquals(listOf(BackRecordEvent.Reason.DEATH, BackRecordEvent.Reason.TELEPORT), records.map { it.reason })
        api.events.subscribe(BackTeleportEvent.TYPE) { it.isCancelled = true }
        run(admin, "back")
        assertEquals(71.0, adminPlayer.location.y)
        // Not on a world that's gone.
        server.loadedWorlds.clear()
        assertNotNull(api.getBackLocation(admin.player!!))
    }

    @Test
    fun reloadAppliesCommandChangesAndBacksListenerFollowsBack() {
        assertNotNull(plugin.dispatcher.tree.root("fly"))
        assertEquals(1, server.watches)
        reload(
            """
            commands:
              fly:
                enabled: false
              speed:
                aliases: [zoom]
              back:
                enabled: false
            info-commands:
              vote:
                message: "Vote!"
              heal:
                message: "clashes"
            """.trimIndent(),
        )
        assertNull(plugin.dispatcher.tree.root("fly"), "turned off")
        assertNull(plugin.dispatcher.tree.root("flight"), "its aliases too")
        assertNull(plugin.dispatcher.tree.root("setspeed"), "old alias gone")
        assertNotNull(plugin.dispatcher.tree.root("zoom"), "new alias added")
        assertEquals(0, server.watches, "/back's listener is gone with /back")
        assertFalse(plugin.isBackEnabled())
        run(console, "vote")
        assertEquals("Vote!", console.replies.last())
        assertTrue(console.replies.any { it.startsWith("» Reloaded xUtilities: 0 modules on.") }, console.replies.toString())
        run(console, "heal admin")
        assertEquals("» You've been healed.", adminPlayer.messages.last(), "/heal is still xUtilities' heal")
        assertTrue(platform.log.lines.any { "info-commands.heal" in it.message })
    }

    @Test
    fun aCommandWhoseAliasIsTakenLosesTheAliasNotTheCommand() {
        reload(
            """
            commands:
              feed:
                aliases: [heal, eat]
            """.trimIndent(),
        )
        assertNotNull(plugin.dispatcher.tree.root("eat"))
        run(console, "heal admin")
        assertEquals("» You've been healed.", adminPlayer.messages.last())
    }

    @Test
    fun aBlankPermissionLetsAnyone() {
        reload(
            """
            commands:
              feed:
                permission: ""
                permission-others: ""
            """.trimIndent(),
        )
        run(steve, "feed admin")
        assertEquals(20, adminPlayer.food)
    }

    @Test
    fun theDefaultConfigWritesEverySetting() {
        val text = Configs.defaultText(XUtilitiesConfig)
        assertTrue("config-version: 3" in text)
        assertTrue("permission-others: \"xutilities.command.fly.others\"" in text)
        assertTrue("sound: \"block.ender_chest.open 0.5\"" in text, text)
        // The default file reads back to the defaults.
        assertEquals(XUtilitiesConfig.defaults, (Configs.load(XUtilitiesConfig, text, TestYaml) as Outcome.Ok).value.config)
        @Suppress("UNUSED_VARIABLE")
        val world = WorldRef.of("world")
    }
}
