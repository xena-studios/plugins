package co.xenastudios.xsample.bukkit

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.event.CancellableEvent
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.XenaCancellableEvent
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.platform.StoragePath
import co.xenastudios.core.common.platform.readText
import co.xenastudios.core.common.platform.writeText
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.every
import co.xenastudios.core.common.scheduler.later
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.common.text.Style
import co.xenastudios.core.common.text.TextColor
import org.bukkit.event.Listener
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap
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
 * them), text and languages. Logs `PLATFORM PASS` once all have, or `PLATFORM FAIL` with what
 * didn't, as an error, which fails the boot test.
 */
internal class PlatformCheck(private val plugin: JavaPlugin, private val platform: BukkitPlatform) {

    private val scheduler = platform.scheduler
    private val waiting = ConcurrentHashMap.newKeySet<String>()
    private val failures = ConcurrentHashMap<String, String>()
    private val finished = AtomicBoolean()

    fun start() {
        val checks = listOf("global", "async", "async delay", "repeat", "place", "retired", "storage", "event", "async event", "text")
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
        platform.text.send(plugin.server.consoleSender, Component("Platform check: text drawn as ${platform.text}", Style(color = TextColor.NAMED.getValue("aqua"))))
        check("text", platform.languages.clientLocale(plugin.server.consoleSender) == null) { "the console has a locale" }

        scheduler.later(Where.Global, 10.seconds) {
            if (waiting.isNotEmpty()) failures["timeout"] = "never finished: ${waiting.sorted().joinToString(", ")}"
            finish()
        }
    }

    private fun checkStorage() {
        val path = StoragePath.of("platform-check/hello.txt")
        val wrote = platform.storage.writeText(path, "hello")
        val read = platform.storage.readText(path)
        val deleted = platform.storage.delete(path)
        check("storage", wrote is Outcome.Ok && (read as? Outcome.Ok)?.value == "hello" && (deleted as? Outcome.Ok)?.value == true) {
            "wrote $wrote, read $read, deleted $deleted"
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
