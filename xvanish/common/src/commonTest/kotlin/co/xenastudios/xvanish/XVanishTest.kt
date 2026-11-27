package co.xenastudios.xvanish

import co.xenastudios.core.api.event.EventListener
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandResult
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.Configs
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.family.PlayerStore
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.testing.FakeCommandSource
import co.xenastudios.core.testing.FakePlatform
import co.xenastudios.core.testing.TestYaml
import co.xenastudios.core.testing.settled
import co.xenastudios.xvanish.api.XVanish
import co.xenastudios.xvanish.api.event.UnvanishedEvent
import co.xenastudios.xvanish.api.event.VanishEvent
import co.xenastudios.xvanish.api.event.VanishedEvent
import co.xenastudios.xvanish.config.XVanishConfig
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * xVanish on the fake platform (Folia's regions, virtual time): every route a vanished player
 * could be seen by, once with each viewer told one by one (Paper, Spigot) and once hidden by
 * default first (Folia).
 */
abstract class XVanishCases(byDefault: Boolean) {
    private val platform = FakePlatform()
    private val server = FakeVanishServer(platform.scheduler, byDefault)
    private val cache = PlayerCache(platform.family.board, PlayerStore.InFile(platform.family.folder), platform.scheduler, platform.clock, platform.log, "xvanish")
    private var config: Config = XVanishConfig.defaults
    private val plugin = XVanishPlugin(platform, server, { loaded() }, server, server, cache)
    private val staff = FakeCommandSource.player("Staff", "xvanish.vanish", "xvanish.vanish.others", "xvanish.list", "xvanish.see", "xvanish.login", "xvanish.pickup")
    private val mod = FakeCommandSource.player("Mod", "xvanish.see")
    private val alex = FakeCommandSource.player("Alex")
    private val steve = FakeCommandSource.player("Steve")
    private val console = FakeCommandSource.console()

    private fun loaded() = Loaded(config, Translations.of(XVanishMessages.catalog, emptyMap()))

    init {
        platform.scheduler.runAs(Where.Global) { plugin.start(loaded()) }
        platform.scheduler.runPending()
        for (each in listOf(staff, mod, alex)) join(each)
    }

    @AfterTest
    fun stop() {
        platform.scheduler.runAs(Where.Global) { plugin.stop() }
        platform.scheduler.runPending()
    }

    private fun join(source: FakeCommandSource): Boolean {
        cache.joined(source.player!!, source.name, null)
        return server.join(source).also { platform.scheduler.tick(2) }
    }

    private fun run(source: FakeCommandSource, line: String): CommandResult {
        source.clear()
        server.sent.clear()
        val where = source.player?.let { Where.on(it) } ?: Where.Global
        val result = platform.scheduler.runAs(where) { plugin.dispatcher.dispatch(source, line) }
        plugin.texts.feedback.message(result, source)?.let(source::reply)
        platform.scheduler.tick(2)
        return result
    }

    private fun reconfigure(edit: String) {
        config = (Configs.load(XVanishConfig, edit, TestYaml) as Outcome.Ok).value.config
        platform.scheduler.runAs(Where.Global) { plugin.apply(loaded()) }
        platform.scheduler.tick(2)
    }

    @Test
    fun vanishingHidesFromPlayersAndNotFromStaff() {
        run(staff, "vanish")
        assertTrue(plugin.isVanished(staff.player!!))
        assertFalse(server.sees(alex, staff), "a player still sees them")
        assertTrue(server.sees(mod, staff), "staff with xvanish.see don't")
        assertTrue(server.sees(staff, alex))
        assertFalse(plugin.canSee(alex.player!!, staff.player!!))
        assertTrue(plugin.canSee(mod.player!!, staff.player!!))
        assertTrue(server.heard(staff).any { "You're vanished" in it })
        // Shown again to everyone.
        run(staff, "vanish")
        assertFalse(plugin.isVanished(staff.player!!))
        assertTrue(server.sees(alex, staff))
        assertTrue(server.heard(staff).any { "visible" in it })
    }

    @Test
    fun theOtherXenaPluginsSeeItInTheFamilysCache() {
        run(staff, "vanish on")
        platform.scheduler.runPending()
        assertTrue(cache.isVanished(staff.player!!))
        run(staff, "vanish off")
        assertFalse(cache.isVanished(staff.player!!))
    }

    @Test
    fun fakeMessagesGoToWhoeverCantSee() {
        run(staff, "vanish")
        assertTrue(server.heard(alex).contains("Staff left the game"), "Alex heard ${server.heard(alex)}")
        assertFalse(server.heard(mod).contains("Staff left the game"), "staff who see them aren't fooled")
        assertTrue(server.heard(mod).any { "Staff vanished" in it })
        assertTrue(server.consoleLines.contains("Staff left the game"))
        run(staff, "vanish")
        assertTrue(server.heard(alex).contains("Staff joined the game"))
        // By hand.
        run(staff, "vanish on")
        run(staff, "vanish login")
        assertTrue(server.heard(alex).contains("Staff joined the game"))
        assertTrue(plugin.isVanished(staff.player!!), "a fake login doesn't show them")
        assertTrue(run(alex, "vanish login") is CommandResult.NoPermission)
    }

    @Test
    fun levelsHideSeniorStaffFromJuniorStaff() {
        staff.permissions += "xvanish.level.3"
        run(staff, "vanish")
        assertEquals(3, plugin.level(staff.player!!))
        assertFalse(server.sees(mod, staff), "level 1 staff don't see level 3")
        mod.permissions += "xvanish.level.3"
        // Their permissions changed: a reload works everyone out again.
        reconfigure("")
        assertTrue(server.sees(mod, staff))
    }

    @Test
    fun aPlayerJoiningDoesntSeeTheVanished() {
        run(staff, "vanish")
        join(steve)
        assertFalse(server.sees(steve, staff), "a joiner sees a vanished player")
        assertTrue(server.sees(staff, steve))
    }

    @Test
    fun aVanishedPlayerStaysVanishedThroughARelogAndWritesTheFile() {
        run(staff, "vanish")
        platform.scheduler.runPending()
        val file = (platform.storage.readText(Remembered.FILE) as Outcome.Ok).value
        assertNotNull(file)
        assertTrue(staff.player.toString() in file, file)
        assertTrue(server.quit(staff), "the quit message is left out")
        assertTrue(join(staff), "joins vanished, the join message left out")
        assertFalse(server.sees(alex, staff))
        assertTrue(server.heard(staff).any { "joined vanished" in it.lowercase() || "joined" in it })
        // A restart: a new plugin reads the file.
        val again = Remembered(platform.storage, platform.scheduler, platform.log)
        again.load()
        assertTrue(staff.player!! in again)
    }

    @Test
    fun withRememberOffARelogShowsThem() {
        reconfigure("vanish:\n  remember: false\n")
        run(staff, "vanish")
        server.quit(staff)
        assertFalse(join(staff))
        assertTrue(server.sees(alex, staff))
    }

    @Test
    fun xvanishJoinJoinsVanishedEveryTime() {
        steve.permissions += "xvanish.join"
        assertTrue(join(steve))
        assertFalse(server.sees(alex, steve))
        assertTrue(server.sees(mod, steve))
    }

    @Test
    fun otherPluginsCanRefuseAndHearIt() {
        val heard = ArrayList<String>()
        plugin.bus.subscribe(VanishedEvent.TYPE, EventListener { heard += "vanished ${it.cause}" })
        plugin.bus.subscribe(UnvanishedEvent.TYPE, EventListener { heard += "unvanished" })
        run(staff, "vanish")
        run(staff, "vanish")
        assertEquals(listOf("vanished COMMAND", "unvanished"), heard)
        val subscription = plugin.bus.subscribe(VanishEvent.TYPE, EventListener { it.isCancelled = true })
        run(staff, "vanish")
        assertFalse(plugin.isVanished(staff.player!!))
        assertTrue(staff.replies.any { "stopped" in it }, "told: ${staff.replies}")
        subscription.cancel()
        // The API, from any thread.
        val done = plugin.setVanished(alex.player!!, true, true)
        platform.scheduler.tick(2)
        assertTrue(done.settled())
        assertTrue(XVanish.api().isVanished(alex.player!!))
        assertFalse(server.heard(steve).contains("Alex left the game"), "silent")
    }

    @Test
    fun staffVanishOthersListThemAndPlayersCant() {
        assertTrue(run(alex, "vanish") is CommandResult.NoPermission)
        run(staff, "vanish Alex")
        assertTrue(plugin.isVanished(alex.player!!))
        assertTrue(staff.replies.any { "Alex is vanished now" in it }, "${staff.replies}")
        run(staff, "vanish list")
        assertTrue(staff.replies.any { "Alex" in it && "level 1" in it }, "${staff.replies}")
        run(staff, "vanish off Alex")
        assertFalse(plugin.isVanished(alex.player!!))
        run(staff, "vanish list")
        assertTrue(staff.replies.any { "Nobody" in it })
        run(console, "vanish")
        assertTrue(console.replies.any { "Only players" in it }, "${console.replies}")
        run(console, "vanish on Staff")
        assertTrue(plugin.isVanished(staff.player!!))
        run(console, "vanish on Nobody")
        assertTrue(console.replies.any { "Nobody" in it })
    }

    @Test
    fun pickupAndChatAreAnsweredFromMemory() {
        run(staff, "vanish")
        assertFalse(plugin.picksUp(staff.player!!))
        run(staff, "vanish pickup")
        assertTrue(plugin.picksUp(staff.player!!))
        // The first public message is held back, the same again within 15 seconds goes.
        assertFalse(plugin.mayChat(staff.player!!, "hi all"))
        assertTrue(server.heard(staff).any { "Say it again" in it })
        assertTrue(plugin.mayChat(staff.player!!, "hi all"))
        assertFalse(plugin.mayChat(staff.player!!, "hi again"))
        platform.scheduler.advance(16.seconds)
        assertFalse(plugin.mayChat(staff.player!!, "hi again"))
        assertTrue(plugin.mayChat(alex.player!!, "not vanished"))
    }

    @Test
    fun placeholdersCountOnlyThoseSeen() {
        assertEquals("3", plugin.placeholders.resolve("online", null))
        run(staff, "vanish")
        assertEquals("2", plugin.placeholders.resolve("online", null))
        assertEquals("1", plugin.placeholders.resolve("count", null))
        assertEquals("true", plugin.placeholders.resolve("vanished", staff.player))
        assertEquals("1", plugin.placeholders.resolve("level", staff.player))
        assertEquals(4, plugin.placeholders.set().placeholders.size)
    }

    @Test
    fun switchesThatAreOffListenToNothing() {
        assertTrue(Switch.SERVER_LIST in server.listening)
        reconfigure("hide:\n  server-list: false\nno-trace:\n  pickup: false\n")
        assertFalse(Switch.SERVER_LIST in server.listening)
        assertFalse(Switch.PICKUP in server.listening)
        assertTrue(Switch.PLAYERS in server.listening)
    }

    @Test
    fun effectsFollowTheConfig() {
        run(staff, "vanish")
        val effects = server.effects[staff.player!!]
        assertNotNull(effects)
        assertTrue(effects.fly)
        assertEquals("You are vanished", effects.bossBar?.plain())
        run(staff, "vanish")
        assertTrue(staff.player!! !in server.effects)
    }

    @Test
    fun otherPluginsPermissionsAreHonouredWhenAsked() {
        steve.permissions += "sv.see"
        join(steve)
        run(staff, "vanish")
        assertFalse(server.sees(steve, staff))
        reconfigure("vanish:\n  other-plugins-permissions: true\n")
        assertTrue(server.sees(steve, staff))
        assertTrue(plugin.permissions.permissions.any { it.node == "sv.use" })
    }

    @Test
    fun xbansStaffSeeTheFirstLevel() {
        steve.permissions += "xbans.staff"
        join(steve)
        run(staff, "vanish")
        assertTrue(server.sees(steve, staff), "xBans' staff see level 1")
        staff.permissions += "xvanish.level.2"
        run(staff, "vanish")
        run(staff, "vanish")
        assertFalse(server.sees(steve, staff), "but not level 2")
        reconfigure("vanish:\n  xbans-staff-see: false\n")
        staff.permissions -= "xvanish.level.2"
        reconfigure("vanish:\n  xbans-staff-see: false\n")
        assertFalse(server.sees(steve, staff))
    }

    @Test
    fun forgetTakesThemOffTheFile() {
        run(staff, "vanish")
        // It asks to be typed twice.
        run(console, "xvanish forget Staff")
        run(console, "xvanish forget Staff")
        platform.scheduler.runPending()
        assertFalse(staff.player!! in plugin.remembered)
    }
}

class XVanishTest : XVanishCases(byDefault = false)

/** As Folia hides them: hidden from everyone at once, then shown to whoever may see. */
class XVanishHiddenByDefaultTest : XVanishCases(byDefault = true)
