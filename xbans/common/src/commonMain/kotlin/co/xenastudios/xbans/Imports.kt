package co.xenastudios.xbans

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.reject
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.xbans.api.Punishment
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.config.XBansConfig
import co.xenastudios.xbans.ip.IpKeys
import co.xenastudios.xbans.model.Entry
import co.xenastudios.xbans.store.Backend
import co.xenastudios.xbans.store.importPlayer
import co.xenastudios.xbans.store.insertImported
import co.xenastudios.xbans.store.touchIp

/** The importers an adapter offers, by the id `/xbans import` takes: `vanilla`, `essentials`, `litebans`, `advancedban`. */
public interface Imports {
    public val ids: List<String>

    public fun make(id: String): Importer?
}

/** Somewhere punishments can be imported from: read whole, off the server's threads, before anything is written. */
public interface Importer {
    /** As messages show it: `LiteBans`. */
    public val name: String

    /** Everything it holds, or [Haul.failed] with why not. [scope] is what a punishment with none gets. Blocks; never on a server thread. */
    public fun read(scope: String, now: Long, context: ImportContext): Haul
}

/**
 * What an importer may ask of the server as it reads: whether a plugin is running here (its
 * database is then in use), and to tell whoever ran the import how it's going. Made per import.
 */
public class ImportContext(
    private val running: (String) -> Boolean = { false },
    private val tell: (MessageKey, Array<out Pair<String, Any>>) -> Unit = { _, _ -> },
) {
    /** Whether the plugin called [name] is installed and on, here. */
    public fun running(name: String): Boolean = running.invoke(name)

    public fun tell(message: MessageKey, vararg placeholders: Pair<String, Any>) {
        tell.invoke(message, placeholders)
    }
}

/** What a source holds, read whole before anything is written, so a dry run can count it. */
public class Haul(
    /** Why it couldn't be read, for the owner, in English; `null` if it was. */
    public val failed: String? = null,
    /** The message saying so, when it's more than [XBansMessages.importFailed]'s reason. */
    public val message: MessageKey? = null,
    public val placeholders: List<Pair<String, Any>> = emptyList(),
) {
    /** One punishment, as xBans will store it. Its [Entry.ip] is the raw address; [key] says where it came from ([IP] stands for its IP key). */
    public class Row(public val entry: Entry, public val key: String)

    /** An account seen on an IP, for `/alts` and `/iphistory`. [ip] is the raw address, or `null`. */
    public class Seen(public val id: PlayerRef, public val name: String?, public val ip: String?, public val first: Long, public val last: Long)

    public val rows: MutableList<Row> = ArrayList()
    public val seen: MutableList<Seen> = ArrayList()

    /** LiteBans' wildcard IP bans (192.168.1.*), left out. */
    public var wildcards: Long = 0

    public fun add(entry: Entry, key: String) {
        rows += Row(entry, key)
    }

    public fun seen(id: PlayerRef, name: String?, ip: String?, first: Long, last: Long) {
        seen += Seen(id, name, ip, first, last)
    }

    public fun count(type: PunishmentType): Long = rows.count { it.entry.type == type }.toLong()

    public fun players(): Long = seen.map { it.id }.distinct().size.toLong()

    public companion object {
        /** In a [Row.key]: the row's IP, as the database keys it (hashed, with hashing on), never the address. */
        public const val IP: String = "{ip}"

        public fun failed(why: String): Haul = Haul(why)

        /** Failed, as [message] says; [why] for the console. */
        public fun failed(why: String, message: MessageKey, vararg placeholders: Pair<String, Any>): Haul = Haul(why, message, placeholders.toList())
    }
}

/**
 * Writes a haul in batches of [BATCH], each one transaction, as 1.x did. A row imported before is
 * skipped, so importing twice adds nothing. What's in force is enforced at once, here and
 * (quietly) on the network's other servers. With `ips.enabled` off, no IP is stored, and a
 * punishment of an IP alone is skipped. Settles with (added, skipped).
 */
internal class Importing(private val plugin: XBansPlugin, private val backend: Backend, private val haul: Haul) {
    private var added = 0L
    private var skipped = 0L
    private val ips = plugin.config[XBansConfig.ips.enabled]
    private val result = newPending<Pair<Long, Long>>()

    fun write(): Pending<Pair<Long, Long>> {
        rows(0)
        return result
    }

    private fun rows(from: Int) {
        if (from >= haul.rows.size) return seen(0)
        val batch = haul.rows.subList(from, minOf(haul.rows.size, from + BATCH))
        val now = plugin.now()
        backend.database.transaction { session ->
            val active = ArrayList<Entry>()
            var a = 0L
            var s = 0L
            for (row in batch) {
                val entry = row.entry
                if (!ips && entry.target == null) {
                    s++
                    continue
                }
                val keyed = keyed(entry)
                val key = if (keyed.ip == null) row.key else row.key.replace(Haul.IP, keyed.ip)
                val id = when (val inserted = backend.insertImported(session, keyed, key)) {
                    is Outcome.Err -> return@transaction inserted
                    is Outcome.Ok -> inserted.value
                }
                if (id < 0) {
                    s++
                    continue
                }
                a++
                val stored = keyed.withId(id)
                if (stored.active(now)) {
                    active += stored
                    backend.publish(session, Backend.TOPIC_PUNISH, "$id${Backend.QUIET}").let { if (it is Outcome.Err) return@transaction it }
                }
            }
            Outcome.Ok(Triple(active, a, s))
        }.onSettled { done, error ->
            val value = (done as? Outcome.Ok)?.value ?: return@onSettled fail(done, error)
            added += value.second
            skipped += value.third
            for (entry in value.first) {
                backend.active.add(entry)
                // Players already online who are now banned are kicked, without a broadcast.
                plugin.punisher.enforce(entry)
            }
            rows(from + BATCH)
        }
    }

    private fun seen(from: Int) {
        if (from >= haul.seen.size) return run { result.resolve(added to skipped) }
        val batch = haul.seen.subList(from, minOf(haul.seen.size, from + BATCH))
        backend.database.transaction { session ->
            for (player in batch) {
                player.name?.let { backend.importPlayer(session, player.id, it, player.first, player.last).let { done -> if (done is Outcome.Err) return@transaction done } }
                if (player.ip != null && ips) {
                    backend.touchIp(session, player.id, backend.keys.key(player.ip), player.first, player.last).let { done -> if (done is Outcome.Err) return@transaction done }
                }
            }
            Outcome.Ok(Unit)
        }.onSettled { done, error ->
            if (done !is Outcome.Ok) return@onSettled fail(done, error)
            seen(from + BATCH)
        }
    }

    private fun fail(done: Any?, error: Throwable?) {
        result.reject(IllegalStateException((done as? Outcome.Err<*>)?.error?.toString() ?: error?.toString() ?: "the database failed"))
    }

    // The IP as this database keys it (or none, with IPs off), and an IP punishment of no account
    // named after it as anyone may see it: masked, or the start of its hash. Who gave it may not be
    // on this server, and their weight is unknown: held as high as the console's, so only they and
    // xbans.unban.override lift it.
    private fun keyed(entry: Entry): Entry {
        val key = if (!ips || entry.ip == null) null else backend.keys.key(entry.ip)
        var p = entry.punishment
        if (p.target == null) {
            p = Punishment(p.id, p.type, null, IpKeys.label(key), p.ipBased, p.reason, p.staff, p.staffName, p.created, p.expires, p.scope, p.silent, p.template, p.removal)
        }
        return Entry(p, key, Entry.CONSOLE)
    }

    companion object {
        const val BATCH = 500
    }
}
