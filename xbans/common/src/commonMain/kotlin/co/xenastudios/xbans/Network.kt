package co.xenastudios.xbans

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Change
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.later
import co.xenastudios.xbans.model.Entry
import co.xenastudios.xbans.store.Backend
import co.xenastudios.xbans.store.find
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Network mode: hears the punishments and revocations other servers (and the proxy) make, through
 * the core's change feed, and makes them felt here as if made here. Each change carries only an
 * id; the punishment is read from the shared database. Off, and free, on a single server.
 * 1.x's protocol, unchanged (decisions/2026-11-18-xbans.md, "Network mode").
 *
 * A change whose punishment can't be read is tried again; if it still can't be, every ban and
 * mute in force is read again ([resync]), so the change isn't lost for good. The same happens
 * whenever the feed says it may have missed changes. One reload runs at a time: one asked for
 * while another runs runs once after it, and one that fails is tried again every [retry] until
 * one works. While a reload is due, changes aren't tried again one by one.
 */
internal class Network(
    private val plugin: XBansPlugin,
    /** Reads one punishment; the database's, in use; tests' can fail. */
    private val reader: (Backend, Long) -> Pending<Outcome<Entry?, DbError>> = { backend, id -> backend.find(id) },
    private val retry: Duration = 15.seconds,
) {
    private val lock = Any()
    private var reloading = false
    private var again = false
    private var failedFor: Backend? = null
    private var tries = 0

    /** Subscribes to a freshly opened database's feed, before it starts. */
    fun subscribe(backend: Backend) {
        val feed = backend.feed ?: return
        feed.subscribe(Backend.TOPIC_PUNISH) { handle(backend, it, revoke = false) }
        feed.subscribe(Backend.TOPIC_REVOKE) { handle(backend, it, revoke = true) }
        // The feed may have missed changes: read again what's in force.
        feed.onResync { resync(backend) }
    }

    /**
     * Reads every ban and mute in force again and brings memory in line, enforcing any ban this
     * server missed (its players are kicked; nothing is broadcast).
     */
    fun resync(backend: Backend) {
        val start = locked(lock) {
            if (reloading) {
                again = true
                false
            } else {
                reloading = true
                true
            }
        }
        if (start) reload(backend)
    }

    private fun reload(backend: Backend) {
        if (backend !== plugin.backend) return done(backend, null) // The database changed: the new one was read whole when it opened.
        backend.reloadInForce(plugin.now()).onSettled { read, error ->
            if (read is Outcome.Ok) {
                if (read.value.isNotEmpty()) {
                    plugin.log.info("Caught up with the network: ${read.value.size} bans and mutes this server had missed.")
                    read.value.forEach(plugin.punisher::enforce)
                }
                done(backend, null)
            } else {
                done(backend, (read as? Outcome.Err)?.error?.toString() ?: error?.toString() ?: "the database failed")
            }
        }
    }

    // After a reload: the next one asked for meanwhile, or the next try at a failed one.
    private fun done(backend: Backend, failure: String?) {
        var recovered = false
        var failures = 0
        val next = locked(lock) {
            reloading = false
            recovered = failure == null && failedFor != null
            if (failure == null || backend !== plugin.backend) {
                failedFor = null
                tries = 0
            } else {
                failedFor = backend
                tries++
            }
            failures = tries
            // A failed one is tried again later anyway, and that covers whatever was asked for meanwhile.
            val next = again && failure == null
            again = false
            next
        }
        if (failure != null && backend === plugin.backend) {
            if (failures == 1) {
                plugin.log.warn("Couldn't read the bans and mutes in force to catch up with the network ($failure); trying again every ${retry.inWholeSeconds}s until it works.")
            }
            if (plugin.running) plugin.platform.scheduler.later(Where.Async, retry) { tryAgain(backend, failures) }
        } else if (recovered) {
            plugin.log.info("Caught up with the network after all.")
        }
        if (next) resync(backend)
    }

    // A failed reload's next try, unless one has worked since (or failed since, which set its own).
    private fun tryAgain(backend: Backend, failures: Int) {
        val go = locked(lock) { failedFor === backend && tries == failures }
        if (go) resync(backend)
    }

    /** One change from the feed, on its reader thread, in order. */
    fun handle(backend: Backend, change: Change, revoke: Boolean) {
        val payload = change.payload
        val replaced = payload.endsWith(Backend.REPLACED)
        val silent = payload.endsWith(Backend.SILENT)
        val quiet = payload.endsWith(Backend.QUIET)
        val extraSilent = payload.endsWith(Backend.EXTRA_SILENT)
        val id = (if (replaced || silent || quiet || extraSilent) payload.dropLast(2) else payload).toLongOrNull()
        if (id == null) {
            plugin.log.warn("Another server sent a change xBans can't read: $payload")
            return
        }
        // The proxy took it in already, from the server's message.
        if (!revoke && !quiet && plugin.server.proxy && !plugin.markHeard(id)) return
        val (waiting, busy) = locked(lock) { (failedFor === backend) to (reloading || failedFor === backend) }
        if (waiting) {
            // The database couldn't be read moments ago, and the reload that's due reads this too.
            if (revoke) backend.active.remove(id)
            return
        }
        read(backend, id, if (busy) 0 else RETRY_MS.size) { entry, failed ->
            if (failed != null) {
                if (!busy) plugin.log.warn("Couldn't read punishment #$id from another server ($failed); reading every ban and mute in force again instead.")
                // Lifted all the same: a ban stays lifted even if its details can't be read.
                if (revoke) backend.active.remove(id)
                resync(backend)
                return@read
            }
            if (backend !== plugin.backend) return@read // The database changed while this was read.
            val punisher = plugin.punisher
            when {
                revoke -> if (entry == null) backend.active.remove(id)
                else punisher.applyRevoked(entry, false, silent || entry.punishment.silent, extraSilent, !replaced, null)
                entry != null && quiet -> {
                    backend.active.add(entry)
                    punisher.enforce(entry)
                }
                entry != null && entry.punishment.removal == null -> punisher.applyMade(entry, emptyList(), false, extraSilent, null)
            }
        }
    }

    private fun read(backend: Backend, id: Long, retries: Int, attempt: Int = 0, done: (Entry?, String?) -> Unit) {
        reader(backend, id).onSettled { read, error ->
            if (read is Outcome.Ok) return@onSettled done(read.value, null)
            val why = (read as? Outcome.Err)?.error?.toString() ?: error?.toString() ?: "the database failed"
            if (attempt >= retries || !plugin.running) return@onSettled done(null, why)
            plugin.platform.scheduler.later(Where.Async, RETRY_MS[attempt]) { read(backend, id, retries, attempt + 1, done) }
        }
    }

    companion object {
        /** Waits between tries at reading a punishment another server made. */
        val RETRY_MS: List<Duration> = listOf(kotlin.time.Duration.parse("250ms"), 1.seconds)
    }
}
