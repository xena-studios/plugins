package co.xenastudios.core.common.platform

import co.xenastudios.core.common.scheduler.Scheduler

/**
 * The server software a plugin is running on, as the core and the plugins' shared code see
 * it. Each platform's adapter makes one at startup, after looking once at what the server can
 * do; nothing asks the server again per call (ROADMAP.md, Architecture).
 *
 * What a server can't do from the stdlib comes from here: Pumpkin's plugins can't read the
 * clock or files themselves (M0), so [clock] and [storage] are always the platform's.
 */
public interface Platform {
    /** The server software, for logs and `/<plugin> debug`, such as `Paper 1.21.11` or `Folia 26.3`. */
    public val name: String

    /** How work is spread over threads here, which decides what [Scheduler] does. */
    public val threading: Threading

    /** What this platform has, found once at startup. */
    public val capabilities: Set<Capability>

    /** Runs every task of the plugin. */
    public val scheduler: Scheduler

    /** The time, from the platform. */
    public val clock: Clock

    /** The plugin's own folder. */
    public val storage: Storage

    /** Where the plugin's console lines go. */
    public val log: Log

    /**
     * Whether this is a development build, which checks it's on the right thread and throws
     * if it isn't (`checkThread`). Release builds don't check.
     */
    public val development: Boolean

    /** Whether the platform has [capability]. */
    public fun has(capability: Capability): Boolean = capability in capabilities
}

/**
 * How a platform runs work, which [Scheduler] follows. The model is Folia's: every place in a
 * world, and every entity, belongs to one thread at a time. The others are that model with
 * fewer threads.
 */
public enum class Threading {
    /**
     * Folia: each region of a world has its own thread, an entity's work runs on its region's,
     * global work on one thread of its own, and async work on a pool.
     */
    REGIONS,

    /**
     * Bukkit, Paper, Fabric and most servers: one main thread for global, place and entity
     * work alike, and a pool for async work.
     */
    MAIN_THREAD,

    /**
     * A proxy (Velocity, BungeeCord): no worlds and no main thread, so the platform gives each
     * plugin one thread of its own for global and player work, which keeps the plugin's state
     * on one thread as a main thread would, and a pool for async work. There are no places
     * ([co.xenastudios.core.common.scheduler.Where.At] is refused), work for a player is
     * retired once they leave the proxy, and delays aren't rounded to ticks: a proxy has none.
     */
    PROXY,

    /**
     * Pumpkin's plugins: no threads at all. Everything, async work too, runs in the
     * server's tick callbacks, one at a time.
     */
    SINGLE,
}

/** Something a platform has or hasn't, decided once at startup. */
public enum class Capability {
    /**
     * Events fired on a plugin's bus are also fired as the platform's own events (Bukkit's),
     * so plugins listening the platform's way, Skript among them, see them.
     */
    NATIVE_EVENTS,

    /** A SQL database through JDBC. Not on Pumpkin, whose plugins have only files (M0). */
    SQL,

    /** Other plugins can call this one's API directly, as objects. On Pumpkin they send bytes instead. */
    SHARED_API,
}
