package co.xenastudios.core.common.module

/**
 * A feature of a plugin that's on or off as its config says, such as a listener or a repeating
 * task. A module that's off costs nothing: it registers nothing and runs nothing (ROADMAP.md,
 * Light). [ModuleSet] turns modules on and off.
 *
 * Everything a module registers goes through its [ModuleContext], which undoes it all when the
 * module turns off, so a module can't leave a listener or a task behind, and runs everything
 * it registered under its containment: a module that throws turns itself off and says why,
 * and the rest of the plugin keeps running (ROADMAP.md, Robust).
 */
public interface Module {
    /**
     * Turns it on: subscribes, schedules and registers through [context]. Throwing, or
     * calling [ModuleContext.fail], leaves it off, with whatever it set up undone.
     */
    public fun enable(context: ModuleContext)

    /**
     * Turns it off, for anything [enable] did that the context doesn't undo by itself, such
     * as saving what's in memory. Called before the context cancels its tasks and
     * subscriptions.
     */
    public fun disable() {}

    /**
     * The config was reloaded and this module is still wanted. Return `true` if it took the
     * new settings in place; `false`, the default, and it's turned off and a new one, made
     * from the new config, turned on.
     */
    public fun reload(context: ModuleContext): Boolean = false
}
