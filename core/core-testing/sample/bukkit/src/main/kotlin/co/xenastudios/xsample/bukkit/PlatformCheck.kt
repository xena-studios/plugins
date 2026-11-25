package co.xenastudios.xsample.bukkit

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.BukkitServer
import co.xenastudios.core.bukkit.XenaCancellableEvent
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.api.toPlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.platform.StoragePath
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.platform.writeText
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.every
import co.xenastudios.core.common.scheduler.later
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Style
import co.xenastudios.core.common.text.TextColor
import co.xenastudios.core.jvm.family.JvmFamilyBoard
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** A pre-event fired on xSample's own bus, which a Bukkit listener cancels. */
internal class CheckEvent(val from: String) : CancellableEvent() {
    companion object {
        val TYPE: EventType<CheckEvent> = EventType.of("xsample:platform_check")
    }
}

/**
 * What the console commands of a boot test can't reach, checked on the real server once it's
 * up: each kind of scheduled work on the thread that owns it, exact async delays, a repeating
 * task that stops when cancelled, an entity's work retired, files in the plugin's folder, the
 * event bridge both ways (a Bukkit listener cancels the core's event, on a server thread and off
 * them), text and languages, the family's board and folder, and the loader's on-demand
 * drivers. Logs `PLATFORM PASS` once all have, or `PLATFORM FAIL` with what
 * didn't, as an error, which fails the boot test.
 */
internal class PlatformCheck(private val plugin: JavaPlugin, private val platform: BukkitPlatform, private val cache: PlayerCache) {

    private val scheduler = platform.scheduler
    private val waiting = ConcurrentHashMap.newKeySet<String>()
    private val failures = ConcurrentHashMap<String, String>()
    private val finished = AtomicBoolean()

    fun start() {
        val checks = listOf("global", "async", "async delay", "repeat", "place", "retired", "storage", "event", "async event", "text", "family", "drivers", "chat")
        waiting += checks

        scheduler.run(Where.Global) {
            check("global", scheduler.owns(Where.Global) && !scheduler.owns(Where.Async)) { "not on the global thread" }
        }
        scheduler.run(Where.Async) {
            check("async", scheduler.owns(Where.Async) && !scheduler.owns(Where.Global)) { "not off the server's threads" }
        }
        val asked = platform.clock.elapsed()
        scheduler.later(Where.Async, 200.milliseconds) {
            val waited = platform.clock.elapsed() - asked
            check("async delay", waited >= 200.milliseconds) { "ran after $waited, not 200ms" }
        }
        val runs = AtomicInteger()
        scheduler.every(Where.Global, 50.milliseconds) { task ->
            if (runs.incrementAndGet() == 3) {
                task.cancel()
                scheduler.later(Where.Global, 200.milliseconds) {
                    check("repeat", runs.get() == 3 && task.isCancelled) { "ran ${runs.get()} times, not 3, or wasn't cancelled" }
                }
            }
        }
        // Far from spawn, so on Folia it's likely another region than the global thread's work.
        val place = Location(plugin.server.worlds.first().ref, 1000.0, 64.0, -1000.0)
        scheduler.run(Where.At(place)) {
            check("place", scheduler.owns(Where.At(place))) { "not on the thread that owns $place" }
        }
        val ran = AtomicBoolean()
        scheduler.run(Where.On(Uuid.random()), retired = { check("retired", !ran.get()) { "ran for an entity that isn't there" } }) {
            ran.set(true)
        }
        scheduler.run(Where.Async) { checkStorage() }
        checkEvents()
        scheduler.run(Where.Global) { checkChat() }
        platform.text.send(plugin.server.consoleSender, Component("Platform check: text drawn as ${platform.text}", Style(color = TextColor.NAMED.getValue("aqua"))))
        check("text", platform.locale(FakeConsole) == null) { "the console has a locale" }
        scheduler.run(Where.Async) { checkFamily() }
        scheduler.run(Where.Async) {
            // Only that it's there, with the jar's pins: fetching would download a driver.
            val fetcher = platform.driverFetcher()
            check("drivers", fetcher != null) { "the plugin's shim gave no on-demand libraries" }
        }

        scheduler.later(Where.Global, 10.seconds) {
            if (waiting.isNotEmpty()) failures["timeout"] = "never finished: ${waiting.sorted().joinToString(", ")}"
            finish()
        }
        watchClients()
    }

    /**
     * A real client's join and quit in the family's player cache, for the boot tests' client
     * step (scripts/budgets/bots/client.js), which joins with its language set to German and
     * leaves in English: logs `CLIENT JOIN PASS` once the cache has them online in German, and
     * `CLIENT QUIT PASS` once it has them offline, in the language they changed to where the
     * server says when a client changes it (1.12 on). Anything else is an error, which fails
     * the boot.
     */
    private fun watchClients() {
        val plugins = plugin.server.pluginManager
        // After the family's own listeners, which are registered first, at the same priority.
        plugins.registerEvent(PlayerJoinEvent::class.java, object : Listener {}, org.bukkit.event.EventPriority.MONITOR, EventExecutor { _, event ->
            if (event !is PlayerJoinEvent) return@EventExecutor
            val name = event.player.name
            // Before 1.12 the client's language arrives just after it joins; the family reads it again at 2 s.
            scheduler.later(Where.on(event.player.uniqueId.toPlayerRef()), 3.seconds) {
                val cached = cache.find(name)
                if (cached != null && cached.online && cached.language?.lowercase()?.startsWith("de") == true) {
                    platform.log.info("CLIENT JOIN PASS: $name is in the player cache, online, in ${cached.language}")
                } else {
                    platform.log.error("CLIENT JOIN FAIL: the player cache has $name as ${describe(cached)}, not online in German")
                }
            }
        }, plugin)
        plugins.registerEvent(PlayerQuitEvent::class.java, object : Listener {}, org.bukkit.event.EventPriority.MONITOR, EventExecutor { _, event ->
            if (event !is PlayerQuitEvent) return@EventExecutor
            val name = event.player.name
            val cached = cache.find(name)
            val changes = platform.server.atLeast(1, 12)
            val language = !changes || cached?.language?.lowercase()?.startsWith("en") == true
            if (cached != null && !cached.online && language) {
                platform.log.info("CLIENT QUIT PASS: $name left, and the player cache has them offline, in ${cached.language}")
            } else {
                platform.log.error("CLIENT QUIT FAIL: the player cache has $name as ${describe(cached)}" + if (changes) ", not offline in English" else ", not offline")
            }
        }, plugin)
    }

    private fun describe(player: co.xenastudios.core.common.family.CachedPlayer?): String =
        if (player == null) "unknown" else "${if (player.online) "online" else "offline"} in ${player.language}"

    private fun checkStorage() {
        val path = StoragePath.of("platform-check/hello.txt")
        val wrote = platform.storage.writeText(path, "hello")
        val read = platform.storage.readText(path)
        val deleted = platform.storage.delete(path)
        check("storage", wrote is Outcome.Ok && (read as? Outcome.Ok)?.value == "hello" && (deleted as? Outcome.Ok)?.value == true) {
            "wrote $wrote, read $read, deleted $deleted"
        }
    }

    // The board is registered where every plugin finds it, the family's file is there, and
    // the player cache reads and writes through the board.
    private fun checkFamily() {
        val services = plugin.server.servicesManager.getRegistrations(ConcurrentMap::class.java)
        val registered = services.any { it.provider === (platform.family.board as JvmFamilyBoard).root && it.plugin === plugin }
        val load = platform.family.load()
        val file = (platform.family.folder.read(Family.FILE) as? Outcome.Ok)?.value != null
        check("family", registered && load.error == null && file && cache.capacity > 0) {
            "board registered: $registered, config.yml loaded: ${load.error?.message ?: "yes"}, written: $file"
        }
    }

    // A Bukkit listener cancels the event; the bus's listener after the bridge must see that.
    private fun checkEvents() {
        val bus = SimpleEventBus(platform.log)
        platform.events.bridge(bus, CheckEvent.TYPE)
        val executor = EventExecutor { _, event ->
            if (event is XenaCancellableEvent && event.type == CheckEvent.TYPE.name) event.isCancelled = true
        }
        plugin.server.pluginManager.registerEvent(XenaCancellableEvent::class.java, object : Listener {}, org.bukkit.event.EventPriority.NORMAL, executor, plugin)
        bus.subscribe(CheckEvent.TYPE, EventPriority.HIGH) { event ->
            val name = if (event.from == "async") "async event" else "event"
            check(name, event.isCancelled) { "the Bukkit listener's cancel didn't reach the bus" }
        }
        scheduler.run(Where.Global) { bus.fire(CheckEvent.TYPE, CheckEvent("global")) }
        scheduler.run(Where.Async) { bus.fire(CheckEvent.TYPE, CheckEvent("async")) }
    }

    /**
     * The chat bridge's path, picked once: Paper's AsyncChatEvent on Paper 1.17+ and Folia,
     * Bukkit's AsyncPlayerChatEvent elsewhere; a bridge registers its listeners (the plugin's
     * slot, MONITOR, and a quit listener for its references) and closing it takes them away.
     * What a chat line looks like is the client step's (scripts/budgets/bots/client.js).
     */
    private fun checkChat() {
        val server = platform.server
        val paper = (server.flavor == BukkitServer.Flavor.PAPER || server.flavor == BukkitServer.Flavor.FOLIA) && server.atLeast(1, 17)
        val expected = if (paper) "Paper's AsyncChatEvent" else "Bukkit's AsyncPlayerChatEvent"
        val before = HandlerList.getRegisteredListeners(plugin).size
        val bridge = platform.chat(EventPriority.LOW)
        val registered = HandlerList.getRegisteredListeners(plugin).size - before
        bridge.close()
        val left = HandlerList.getRegisteredListeners(plugin).size - before
        check("chat", platform.chatPath == expected && registered == 3 && left == 0) {
            "chat through ${platform.chatPath} (expected $expected), $registered listeners registered (expected 3), $left left after closing"
        }
    }

    // The console, as a command source: it has no client, so no locale.
    private object FakeConsole : CommandSource {
        override val name: String get() = "CONSOLE"
        override val player: PlayerRef? get() = null

        override fun hasPermission(node: String): Boolean = true

        override fun reply(message: Component) {}
    }

    private fun check(name: String, passed: Boolean, why: () -> String) {
        if (!passed) failures[name] = why()
        if (waiting.remove(name) && waiting.isEmpty()) finish()
    }

    private fun finish() {
        if (!finished.compareAndSet(false, true)) return
        if (failures.isEmpty()) {
            platform.log.info("PLATFORM PASS on ${platform.name}: ${platform.threading} threading, text as ${platform.text}")
        } else {
            platform.log.error("PLATFORM FAIL on ${platform.name}: " + failures.entries.sortedBy { it.key }.joinToString("; ") { "${it.key}: ${it.value}" })
        }
    }
}
