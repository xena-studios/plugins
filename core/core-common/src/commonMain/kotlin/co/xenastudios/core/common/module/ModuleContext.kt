package co.xenastudios.core.common.module

import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventBus
import co.xenastudios.core.api.event.EventListener
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.event.EventType
import co.xenastudios.core.api.event.Subscription
import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.Scheduler
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import kotlin.concurrent.Volatile
import kotlin.time.Duration

/**
 * What a [Module] registers things through while it's on. When the module turns off, or fails,
 * its tasks are cancelled, its subscriptions cancelled and its [onDisable] actions run. Once it's
 * off, nothing it scheduled or subscribed runs again, even a task already queued, and asking for
 * more does nothing.
 *
 * Everything run through it is contained: a task, a listener or a [guard]ed callback that throws
 * turns the module off, with the error logged, and nothing else.
 */
public class ModuleContext internal constructor(
    /** The module's key, as the config and the logs name it. */
    public val key: String,
    /** The platform the plugin is on. */
    public val platform: Platform,
    private val failed: (ModuleContext, String, Throwable?) -> Unit,
) {
    private val lock = Any()

    @Volatile
    private var active: Boolean = true

    private val tasks = LinkedHashSet<ModuleTask>()
    private val subscriptions = ArrayList<Subscription>()
    private val cleanups = ArrayList<() -> Unit>()

    /** Whether the module is on. Once it's `false`, it never turns `true` again: a module turned back on gets a new context. */
    public val isActive: Boolean get() = active

    /**
     * The platform's scheduler, for this module: its tasks are cancelled when the module turns
     * off, and one that throws turns the module off.
     */
    public val scheduler: Scheduler = object : Scheduler {
        override fun run(
            where: Where,
            delay: Duration,
            period: Duration?,
            retired: (() -> Unit)?,
            action: (Task) -> Unit,
        ): Task = schedule(where, delay, period, retired, action)

        override fun owns(where: Where): Boolean = platform.scheduler.owns(where)
    }

    /**
     * [listener] hears events of [type] on [bus] until the module turns off. One that throws
     * turns the module off. Does nothing once it's off.
     */
    public fun <E : Event> subscribe(
        bus: EventBus,
        type: EventType<E>,
        priority: EventPriority = EventPriority.NORMAL,
        ignoreCancelled: Boolean = false,
        listener: (E) -> Unit,
    ): Subscription {
        if (!active) return Inactive
        val subscription = bus.subscribe(type, priority, ignoreCancelled, EventListener<E> { guard { listener(it) } })
        if (!track { subscriptions += subscription }) subscription.cancel()
        return subscription
    }

    /**
     * Runs [action] when the module turns off: for undoing what a platform's adapter
     * registered for it, such as a Bukkit listener. Runs it now if the module is already off.
     */
    public fun onDisable(action: () -> Unit) {
        if (!track { cleanups += action }) runContained("undoing something", action)
    }

    /**
     * Runs [action] under the module's containment: if it throws, the module turns off and says
     * why. For callbacks the platform calls directly, such as a Bukkit event handler. Does
     * nothing once the module is off.
     */
    public fun guard(action: () -> Unit) {
        if (!active) return
        try {
            action()
        } catch (e: Throwable) {
            if (isFatal(e)) throw e
            fail("it threw ${e::class.simpleName}: ${e.message}", e)
        }
    }

    /**
     * Turns the module off, saying why in the console: for a module that finds it can't work,
     * such as a hook whose plugin is missing. The rest of the plugin keeps running; the next
     * reload tries the module again. Does nothing if it's already off.
     */
    public fun fail(reason: String, cause: Throwable? = null) {
        if (!deactivate()) return
        failed(this, reason, cause)
    }

    // Returns whether it was on.
    internal fun deactivate(): Boolean = locked(lock) {
        val was = active
        active = false
        was
    }

    /** Cancels and runs everything registered through it. Called once it's [deactivate]d, on the global thread. */
    internal fun release() {
        val (taskList, subscriptionList, cleanupList) = locked(lock) {
            Triple(tasks.toList(), subscriptions.toList(), cleanups.toList()).also {
                tasks.clear()
                subscriptions.clear()
                cleanups.clear()
            }
        }
        taskList.forEach { it.cancel() }
        subscriptionList.forEach { it.cancel() }
        cleanupList.forEach { runContained("undoing something", it) }
    }

    internal fun runContained(what: String, action: () -> Unit) {
        try {
            action()
        } catch (e: Throwable) {
            if (isFatal(e)) throw e
            platform.log.error("Module $key failed $what while turning off", e)
        }
    }

    // Adds to the module's registrations while it's on; returns false, adding nothing, if it's off.
    private fun track(add: () -> Unit): Boolean = locked(lock) {
        if (active) add()
        active
    }

    private fun schedule(where: Where, delay: Duration, period: Duration?, retired: (() -> Unit)?, action: (Task) -> Unit): Task {
        val task = ModuleTask()
        if (!track { tasks += task }) return task.also { it.cancelled = true }
        val onRetired: (() -> Unit) = {
            forget(task)
            if (retired != null) guard(retired)
        }
        val inner = platform.scheduler.run(where, delay, period, onRetired) { platformTask ->
            if (!active || task.cancelled) {
                platformTask.cancel()
            } else {
                guard { action(task) }
                if (period == null) forget(task)
            }
        }
        task.inner = inner
        // Cancelled while it was being scheduled, by the module or by turning off.
        if (task.cancelled || !active) inner.cancel()
        return task
    }

    private fun forget(task: ModuleTask) {
        locked(lock) { tasks -= task }
    }

    private inner class ModuleTask : Task {
        @Volatile
        var inner: Task? = null

        @Volatile
        var cancelled: Boolean = false

        override val isCancelled: Boolean get() = cancelled || inner?.isCancelled == true

        override fun cancel() {
            cancelled = true
            inner?.cancel()
            forget(this)
        }
    }

    private object Inactive : Subscription {
        override val isActive: Boolean get() = false

        override fun cancel() {}
    }
}
