package co.xenastudios.core.common.module

import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.checkThread

/**
 * A plugin's modules: which are on, which are off, and which failed and why. The plugin says
 * which modules its config wants, by key, at startup and on each reload ([sync]); the set turns
 * on the new ones, reloads the ones that stay, and turns off the ones no longer wanted.
 *
 * A module that fails, turning on or later, is turned off with whatever it registered, and
 * logged with the reason; [status] keeps the reason for `/<plugin> debug`. The rest of the
 * plugin keeps running, and the next [sync] tries the module again.
 *
 * [sync] and [clear] run on the global thread. A module can fail on any thread; it stops at
 * once, and is cleaned up on the global thread.
 */
public class ModuleSet(private val platform: Platform) {

    private class Running(val module: Module, val context: ModuleContext)

    private val lock = Any()
    private val running = LinkedHashMap<String, Running>()
    private val failures = LinkedHashMap<String, ModuleStatus.Failed>()

    /**
     * Makes the modules on exactly those in [wanted], each made by its factory when it's turned
     * on, in [wanted]'s order. A module that was already on is reloaded ([Module.reload]),
     * and one that doesn't reload in place is replaced by a new one from its factory.
     */
    public fun sync(wanted: Map<String, () -> Module>) {
        platform.checkThread(Where.Global) { "Turning modules on and off" }
        val current = locked(lock) { running.toMap() }
        for ((key, run) in current) {
            if (key !in wanted) stop(key, run)
        }
        for ((key, factory) in wanted) {
            val run = current[key]
            if (run != null && run.context.isActive) {
                var inPlace = false
                run.context.guard { inPlace = run.module.reload(run.context) }
                // One that failed reloading is off now, and stays off until the next sync.
                if (inPlace || !run.context.isActive) continue
                stop(key, run)
            }
            start(key, factory)
        }
    }

    /** Turns every module off, as the plugin is disabled. */
    public fun clear() {
        sync(emptyMap())
        locked(lock) { failures.clear() }
    }

    /** Whether the module with this [key] is on, off, or failed and why. */
    public fun status(key: String): ModuleStatus = locked(lock) {
        when {
            running[key]?.context?.isActive == true -> ModuleStatus.On
            else -> failures[key] ?: ModuleStatus.Off
        }
    }

    /** The keys of the modules that are on, in the order they were turned on. */
    public val active: List<String> get() = locked(lock) { running.filterValues { it.context.isActive }.keys.toList() }

    /** The modules that failed since they were last wanted, by key, with why. */
    public val failed: Map<String, ModuleStatus.Failed> get() = locked(lock) { failures.toMap() }

    private fun start(key: String, factory: () -> Module) {
        locked(lock) { failures.remove(key) }
        val context = ModuleContext(key, platform, ::onFailure)
        val module = try {
            factory()
        } catch (e: Throwable) {
            if (isFatal(e)) throw e
            context.deactivate()
            record(key, "it couldn't be made: ${e::class.simpleName}: ${e.message}", e)
            return
        }
        val run = Running(module, context)
        locked(lock) { running[key] = run }
        context.guard { module.enable(context) }
    }

    private fun stop(key: String, run: Running) {
        locked(lock) { if (running[key] === run) running.remove(key) }
        if (run.context.deactivate()) tearDown(run)
    }

    private fun tearDown(run: Running) {
        run.context.runContained("in disable()") { run.module.disable() }
        run.context.release()
    }

    // A module's context calls this when it fails, on whatever thread it failed on.
    private fun onFailure(context: ModuleContext, reason: String, cause: Throwable?) {
        val run = locked(lock) {
            running[context.key]?.takeIf { it.context === context }?.also { running.remove(context.key) }
        }
        record(context.key, reason, cause)
        if (run == null) return
        if (platform.scheduler.owns(Where.Global)) {
            tearDown(run)
        } else {
            platform.scheduler.run(Where.Global) { tearDown(run) }
        }
    }

    private fun record(key: String, reason: String, cause: Throwable?) {
        locked(lock) { failures[key] = ModuleStatus.Failed(reason, cause) }
        platform.log.error("Module $key turned itself off, and the rest of the plugin keeps running: $reason", cause)
    }
}

/** Whether a module is on, off, or failed. */
public sealed class ModuleStatus {
    /** On, and running. */
    public data object On : ModuleStatus()

    /** Off: its config doesn't want it, or the plugin is disabled. */
    public data object Off : ModuleStatus()

    /** Off because it failed: [reason] says why, for the console and `/<plugin> debug`. */
    public data class Failed(public val reason: String, public val cause: Throwable?) : ModuleStatus()
}
