package co.xenastudios.core.sample

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.WorldRef
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.family.CachedPlayer
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.StoragePath
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.platform.writeText
import co.xenastudios.core.common.proxy.ProxyJoinEvent
import co.xenastudios.core.common.proxy.ProxyQuitEvent
import co.xenastudios.core.common.proxy.ServerSwitchEvent
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.every
import co.xenastudios.core.common.scheduler.later
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * What a proxy's console commands can't reach, checked on a real Velocity or BungeeCord once it's
 * up, as xSample's `PlatformCheck` does on Bukkit: the proxy's threads (global work on the
 * plugin's own thread, async off it, exact delays, a repeating task that stops, a player's work
 * retired, no places), files in the plugin's folder, and the family's board shared and its file
 * read. Logs `PLATFORM PASS`, or `PLATFORM FAIL` with what didn't, as an error, which fails the
 * boot test.
 *
 * Then, for a real client through the proxy, `CLIENT JOIN PASS` once the family's player cache has
 * them online in German, `CLIENT SWITCH PASS` once they're on a server, and `CLIENT QUIT PASS` once
 * they've left, in the English they changed to.
 */
public class ProxyCheck(
    private val platform: Platform,
    private val events: EventBus,
    private val family: Family,
    private val cache: PlayerCache,
    /** Whether this plugin's main object hands out the family's board, where the others find it. */
    private val shared: () -> Boolean,
) {
    private val scheduler = platform.scheduler
    private val waiting = ConcurrentHashMap.newKeySet<String>()
    private val failures = ConcurrentHashMap<String, String>()
    private val finished = AtomicBoolean()

    public fun start() {
        waiting += listOf("threading", "global", "async", "async delay", "repeat", "retired", "no places", "storage", "family")
        check("threading", platform.threading == Threading.PROXY) { "${platform.threading}, not PROXY" }
        scheduler.run(Where.Global) {
            check("global", scheduler.owns(Where.Global) && !scheduler.owns(Where.Async)) { "not on the plugin's thread" }
        }
        scheduler.run(Where.Async) {
            check("async", scheduler.owns(Where.Async) && !scheduler.owns(Where.Global)) { "on the plugin's thread" }
        }
        val asked = platform.clock.elapsed()
        scheduler.later(Where.Global, 200.milliseconds) {
            val waited = platform.clock.elapsed() - asked
            check("async delay", waited >= 200.milliseconds && waited < 2.seconds) { "ran after $waited, not 200ms" }
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
        val ran = AtomicBoolean()
        scheduler.run(Where.On(Uuid.random()), retired = { check("retired", !ran.get()) { "ran for a player who isn't on" } }) { ran.set(true) }
        val refused = try {
            scheduler.run(Where.At(Location(WorldRef.of("world"), 0.0, 0.0, 0.0))) {}
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        check("no places", refused) { "work at a place was taken" }
        scheduler.run(Where.Async) { checkStorage() }
        scheduler.run(Where.Async) { checkFamily() }
        scheduler.later(Where.Global, 10.seconds) {
            if (waiting.isNotEmpty()) failures["timeout"] = "never finished: ${waiting.sorted().joinToString(", ")}"
            finish()
        }
        watchClients()
    }

    private fun watchClients() {
        events.subscribe(ProxyJoinEvent.TYPE) { event ->
            // The client's settings come just after the login.
            scheduler.later(Where.On(event.player.uuid), 3.seconds) {
                val cached = cache.find(event.name)
                if (cached != null && cached.online && cached.language?.lowercase()?.startsWith("de") == true) {
                    platform.log.info("CLIENT JOIN PASS: ${event.name} is in the player cache, online, in ${cached.language}")
                } else {
                    platform.log.error("CLIENT JOIN FAIL: the player cache has ${event.name} as ${describe(cached)}, not online in German")
                }
            }
        }
        events.subscribe(ServerSwitchEvent.TYPE) { event ->
            platform.log.info("CLIENT SWITCH PASS: ${event.player} is on ${event.to}" + (event.from?.let { ", from $it" } ?: ""))
        }
        events.subscribe(ProxyQuitEvent.TYPE) { event ->
            // After the family's own record of the quit, which may come later in the same event.
            scheduler.later(Where.Global, 500.milliseconds) {
                val cached = cache.find(event.name)
                if (cached != null && !cached.online && cached.language?.lowercase()?.startsWith("en") == true) {
                    platform.log.info("CLIENT QUIT PASS: ${event.name} left, and the player cache has them offline, in ${cached.language}")
                } else {
                    platform.log.error("CLIENT QUIT FAIL: the player cache has ${event.name} as ${describe(cached)}, not offline in English")
                }
            }
        }
    }

    private fun describe(player: CachedPlayer?): String =
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

    private fun checkFamily() {
        val load = family.load()
        val file = (family.folder.read(Family.FILE) as? Outcome.Ok)?.value != null
        val shares = shared()
        check("family", shares && load.error == null && file && cache.capacity > 0) {
            "board handed out: $shares, config.yml loaded: ${load.error?.message ?: "yes"}, written: $file"
        }
    }

    private fun check(name: String, passed: Boolean, why: () -> String) {
        if (!passed) failures[name] = why()
        if (waiting.remove(name) && waiting.isEmpty()) finish()
    }

    private fun finish() {
        if (!finished.compareAndSet(false, true)) return
        if (failures.isEmpty()) {
            platform.log.info("PLATFORM PASS on ${platform.name}: ${platform.threading} threading")
        } else {
            platform.log.error("PLATFORM FAIL on ${platform.name}: " + failures.entries.sortedBy { it.key }.joinToString("; ") { "${it.key}: ${it.value}" })
        }
    }
}
