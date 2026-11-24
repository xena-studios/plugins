package co.xenastudios.core.common.module

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.common.event.SimpleEventBus
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.WrongThreadException
import co.xenastudios.core.common.scheduler.async
import co.xenastudios.core.common.scheduler.every
import co.xenastudios.core.common.scheduler.global
import co.xenastudios.core.testing.FakePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ModuleSetTest {

    private class Chat(val text: String) : Event

    private val chat = EventType.of<Chat>("test:chat")
    private val platform = FakePlatform()
    private val bus = SimpleEventBus(platform.log)
    private val modules = ModuleSet(platform)
    private val heard = mutableListOf<String>()
    private var ticks = 0

    /** Listens to chat and counts every second, as a typical module does. */
    private inner class Counter(
        private val failOnEnable: Boolean = false,
        private val reloadsInPlace: Boolean = false,
        private val label: String = "",
    ) : Module {
        var disabled = 0
        var reloads = 0

        override fun enable(context: ModuleContext) {
            context.subscribe(bus, chat) { heard += label + it.text }
            context.scheduler.every(Where.Global, 1.seconds) { ticks++ }
            if (failOnEnable) error("no database")
        }

        override fun disable() {
            disabled++
        }

        override fun reload(context: ModuleContext): Boolean {
            reloads++
            return reloadsInPlace
        }
    }

    @Test
    fun aModuleThatsOnListensAndRuns() {
        modules.sync(mapOf("counter" to { Counter() }))
        bus.fire(chat, Chat("hi"))
        platform.scheduler.advance(3.seconds)
        assertEquals(listOf("hi"), heard)
        assertEquals(3, ticks)
        assertEquals(ModuleStatus.On, modules.status("counter"))
        assertEquals(listOf("counter"), modules.active)
    }

    @Test
    fun aModuleThatsOffRegistersNothingAndRunsNothing() {
        val counter = Counter()
        modules.sync(mapOf("counter" to { counter }))
        modules.sync(emptyMap())
        assertEquals(1, counter.disabled)
        assertEquals(0, platform.scheduler.pending)
        assertFalse(bus.hasListeners(chat))
        bus.fire(chat, Chat("hi"))
        platform.scheduler.advance(3.seconds)
        assertEquals(emptyList(), heard)
        assertEquals(0, ticks)
        assertEquals(ModuleStatus.Off, modules.status("counter"))
    }

    @Test
    fun aModuleThatFailsToTurnOnStaysOffAndSaysWhy() {
        val broken = Counter(failOnEnable = true)
        modules.sync(mapOf("broken" to { broken }, "fine" to { Counter(label = "fine ") }))
        val status = modules.status("broken")
        assertIs<ModuleStatus.Failed>(status)
        assertEquals("it threw IllegalStateException: no database", status.reason)
        assertEquals(
            listOf("Module broken turned itself off, and the rest of the plugin keeps running: it threw IllegalStateException: no database"),
            platform.log.errors,
        )
        // What it registered before failing is gone; the other module runs.
        assertEquals(1, broken.disabled)
        bus.fire(chat, Chat("hi"))
        platform.scheduler.advance(1.seconds)
        assertEquals(listOf("fine hi"), heard)
        assertEquals(1, ticks)
        assertEquals(listOf("fine"), modules.active)
        assertEquals(setOf("broken"), modules.failed.keys)
    }

    @Test
    fun aModuleWhoseFactoryThrowsStaysOff() {
        modules.sync(mapOf("broken" to { error("bad config") }))
        assertIs<ModuleStatus.Failed>(modules.status("broken"))
        assertEquals(1, platform.log.errors.size)
    }

    @Test
    fun aListenerThatThrowsTurnsItsModuleOff() {
        modules.sync(
            mapOf(
                "picky" to {
                    object : Module {
                        override fun enable(context: ModuleContext) {
                            context.subscribe(bus, chat) { if (it.text == "boom") error("can't handle it") }
                            context.scheduler.every(Where.Global, 1.seconds) { ticks++ }
                        }
                    }
                },
                "counter" to { Counter() },
            ),
        )
        bus.fire(chat, Chat("boom"))
        assertIs<ModuleStatus.Failed>(modules.status("picky"))
        assertEquals(ModuleStatus.On, modules.status("counter"))
        platform.scheduler.advance(2.seconds)
        // Only the counter's task still runs; the bus didn't log it a second time.
        assertEquals(2, ticks)
        assertEquals(1, platform.log.errors.size)
        assertEquals(listOf("boom"), heard)
    }

    @Test
    fun aTaskThatThrowsTurnsItsModuleOff() {
        modules.sync(
            mapOf(
                "flaky" to {
                    object : Module {
                        override fun enable(context: ModuleContext) {
                            context.subscribe(bus, chat) { heard += it.text }
                            context.scheduler.every(Where.Global, 1.seconds) { if (++ticks == 2) error("lost the connection") }
                        }
                    }
                },
            ),
        )
        platform.scheduler.advance(10.seconds)
        assertEquals(2, ticks)
        assertEquals(0, platform.scheduler.pending)
        assertFalse(bus.hasListeners(chat))
        assertEquals("it threw IllegalStateException: lost the connection", (modules.status("flaky") as ModuleStatus.Failed).reason)
    }

    @Test
    fun aModuleFailingOffTheGlobalThreadStopsAtOnceAndIsCleanedUpThere() {
        var disabledOn: Where? = null
        var asyncRuns = 0
        modules.sync(
            mapOf(
                "uploader" to {
                    object : Module {
                        override fun enable(context: ModuleContext) {
                            context.scheduler.every(Where.Async, 1.seconds) { if (++asyncRuns == 1) error("upload failed") }
                            context.subscribe(bus, chat) { heard += it.text }
                        }

                        override fun disable() {
                            disabledOn = platform.scheduler.current
                        }
                    }
                },
            ),
        )
        platform.scheduler.advance(1.seconds)
        assertIs<ModuleStatus.Failed>(modules.status("uploader"))
        // Off at once: its listener is still registered until the cleanup, but hears nothing.
        bus.fire(chat, Chat("hi"))
        assertEquals(emptyList(), heard)
        platform.scheduler.tick()
        assertEquals(Where.Global, disabledOn)
        assertFalse(bus.hasListeners(chat))
        platform.scheduler.advance(5.seconds)
        assertEquals(1, asyncRuns)
        assertEquals(0, platform.scheduler.pending)
    }

    @Test
    fun aModuleCanTurnItselfOff() {
        modules.sync(
            mapOf(
                "hook" to {
                    object : Module {
                        override fun enable(context: ModuleContext) {
                            context.fail("Vault isn't installed")
                            context.scheduler.global { ticks++ }
                        }
                    }
                },
            ),
        )
        platform.scheduler.tick()
        assertEquals(0, ticks)
        assertEquals("Vault isn't installed", (modules.status("hook") as ModuleStatus.Failed).reason)
    }

    @Test
    fun theNextSyncTriesAFailedModuleAgain() {
        var attempts = 0
        val wanted = mapOf("flaky" to { Counter(failOnEnable = ++attempts == 1) })
        modules.sync(wanted)
        assertIs<ModuleStatus.Failed>(modules.status("flaky"))
        modules.sync(wanted)
        assertEquals(ModuleStatus.On, modules.status("flaky"))
        assertEquals(emptyMap(), modules.failed)
    }

    @Test
    fun aReloadReplacesAModuleUnlessItReloadsInPlace() {
        val first = Counter(label = "old ")
        val inPlace = Counter(reloadsInPlace = true, label = "kept ")
        modules.sync(mapOf("replaced" to { first }, "kept" to { inPlace }))
        val second = Counter(label = "new ")
        modules.sync(mapOf("replaced" to { second }, "kept" to { Counter(label = "unused ") }))
        assertEquals(1, first.reloads)
        assertEquals(1, first.disabled)
        assertEquals(1, inPlace.reloads)
        assertEquals(0, inPlace.disabled)
        bus.fire(chat, Chat("hi"))
        assertEquals(listOf("kept hi", "new hi"), heard)
        platform.scheduler.advance(1.seconds)
        assertEquals(2, ticks)
    }

    @Test
    fun clearTurnsEverythingOffEvenIfADisableThrows() {
        modules.sync(
            mapOf(
                "messy" to {
                    object : Module {
                        override fun enable(context: ModuleContext) {
                            context.scheduler.every(Where.Global, 1.seconds) { ticks++ }
                            context.onDisable { heard += "undone" }
                        }

                        override fun disable() = error("couldn't save")
                    }
                },
                "counter" to { Counter() },
            ),
        )
        modules.clear()
        assertEquals(emptyList(), modules.active)
        assertEquals(0, platform.scheduler.pending)
        assertEquals(listOf("undone"), heard)
        assertEquals(listOf("Module messy failed in disable() while turning off"), platform.log.errors)
    }

    @Test
    fun aContextThatsOffDoesNothing() {
        lateinit var kept: ModuleContext
        modules.sync(mapOf("leaky" to { object : Module { override fun enable(context: ModuleContext) { kept = context } } }))
        modules.sync(emptyMap())
        assertFalse(kept.isActive)
        val task = kept.scheduler.global { ticks++ }
        assertTrue(task.isCancelled)
        assertFalse(kept.subscribe(bus, chat) { heard += it.text }.isActive)
        var undone = false
        kept.onDisable { undone = true }
        assertTrue(undone)
        platform.scheduler.tick()
        assertEquals(0, ticks)
        assertEquals(0, platform.scheduler.pending)
        assertFalse(bus.hasListeners(chat))
    }

    @Test
    fun turningModulesOnAndOffHappensOnTheGlobalThread() {
        platform.scheduler.async { assertFailsWith<WrongThreadException> { modules.sync(emptyMap()) } }
        platform.scheduler.runPending()
    }
}
