package co.xenastudios.xlimbo

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleContext
import co.xenastudios.core.common.scheduler.Task
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.every
import co.xenastudios.xlimbo.api.ReturnCause
import co.xenastudios.xlimbo.api.ReturnRoute
import co.xenastudios.xlimbo.api.event.MainServerStatusEvent
import co.xenastudios.xlimbo.config.XLimboConfig
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.seconds

/**
 * Sends players back on their own: after auto-join's delay, or as soon as server-check sees the
 * main server up, a few a second so it isn't flooded. Everything but the status ping runs on the
 * server thread. A module, keyed by server-check's interval, so changing it on reload starts the
 * ping again.
 */
public class Returns internal constructor(private val plugin: XLimboPlugin) : Module {
    private val queue = LinkedHashSet<PlayerRef>()
    private val history get() = plugin.history

    // Null until the first answer, so turning server-check on never sends players to a server
    // nobody has asked about yet, or announces one that never went down.
    @Volatile
    public var up: Boolean? = null
        private set

    @Volatile
    private var cameUp = false

    private var badAddress: String? = null

    /** Each queued player's place in line, from 1: a snapshot the action bar and the API read. */
    @Volatile
    public var positions: Map<PlayerRef, Int> = emptyMap()
        private set

    // Places as the action bar shows them: vanished players aren't counted for anyone else.
    @Volatile
    internal var shown: Shown = Shown.NONE
        private set

    private var checking = false

    override fun enable(context: ModuleContext) {
        val now = plugin.now()
        // Kept from before a reload restarted the queue; only players nobody saw arrive start now.
        for (player in plugin.server.online()) history.arrivedIfUnknown(player, now)
        history.prune(now)
        context.scheduler.every(Where.Global, 1.seconds) { tick() }
        val config = plugin.config
        checking = plugin.route == ReturnRoute.PROXY && config[XLimboConfig.serverCheck.enabled]
        if (checking) {
            context.scheduler.every(Where.Async, config[XLimboConfig.serverCheck.intervalSeconds].seconds, delay = kotlin.time.Duration.ZERO) { ping(it) }
        } else {
            // Auto-join only, or a teleport back: nothing to ask, so the main server counts as up.
            up = true
        }
        plugin.returns = this
    }

    override fun disable() {
        if (plugin.returns === this) plugin.returns = null
    }

    // Everything but its key is read as it runs: a reload keeps the line, the status and who's announced.
    override fun reload(context: ModuleContext): Boolean = true

    /**
     * Puts a player at the end of the line, for the API and `/xlimbo back`. Server thread. Does
     * nothing while the main server is down (or not yet asked about): the line is emptied then,
     * and everyone goes back when it comes up. Whether they're in line now.
     */
    internal fun enqueue(player: PlayerRef): Boolean {
        if (up != true) return false
        if (queue.add(player)) publish()
        return true
    }

    /** A player sent themselves with /join: out of line, and not sent again while they connect. */
    internal fun sentByHand(player: PlayerRef) {
        queue.remove(player)
        history.tried(player, plugin.now())
        publish()
    }

    internal fun left(player: PlayerRef) {
        if (queue.remove(player)) publish()
    }

    // Off the server thread: asks the main server for its status, every interval-seconds.
    private fun ping(@Suppress("UNUSED_PARAMETER") task: Task) {
        val address = plugin.config[XLimboConfig.serverCheck.address]
        val parsed = StatusAddress.parse(address)
        if (parsed is StatusAddress.Bad) {
            if (address != badAddress) {
                badAddress = address
                plugin.log.warn("config.yml: server-check.address ${parsed.why}; nobody is sent back until it's fixed.")
            }
            answered(false)
            return
        }
        badAddress = null
        val ok = parsed as StatusAddress.Ok
        answered(plugin.pinger.isUp(ok.host, ok.port))
    }

    /** Keeps a status check's answer, then tells listeners if it changed. Off the server thread. */
    internal fun answered(answer: Boolean) {
        val before = up
        if (answer && before == false) cameUp = true
        // Kept first, so a listener asking the API sees the new status.
        up = answer
        if (before != null && before != answer) plugin.fire(MainServerStatusEvent.TYPE) { MainServerStatusEvent(answer) }
    }

    // Once a second on the server thread: fills the line, then sends the next few.
    internal fun tick() {
        val config = plugin.config
        val now = plugin.now()
        if (up != true) {
            if (queue.isNotEmpty()) {
                queue.clear()
                publish()
            }
            return
        }
        val announce = cameUp
        cameUp = false
        val server = plugin.server
        val online = server.online()
        if (announce && checking) for (player in online) plugin.texts.send(player, XLimboMessages.serverUp)
        val autoJoin = config[XLimboConfig.autoJoin.enabled]
        val delay = config[XLimboConfig.autoJoin.delaySeconds] * 1000L
        var changed = false
        for (player in online) {
            if (player in queue || !server.isInLimbo(player) || history.recentlySent(player, now) || history.triedTooOften(player, now)) continue
            val here = history.here(player, now)
            // With server-check, everyone goes back once it's up: at once when it comes back, after
            // a moment for someone who arrives while it's up. Without, after auto-join's delay.
            val due = if (checking) announce || here >= GRACE_MILLIS else autoJoin && here >= delay
            if (due && queue.add(player)) changed = true
        }
        if (queue.isNotEmpty()) {
            if (plugin.hasPriorities()) reorder()
            val perSecond = config[XLimboConfig.queue.perSecond]
            var sending = if (perSecond == 0) queue.size else perSecond
            val next = queue.iterator()
            while (sending > 0 && next.hasNext()) {
                val player = next.next()
                next.remove()
                changed = true
                if (server.name(player) == null || !server.isInLimbo(player)) continue
                sending--
                plugin.sendNow(player, ReturnCause.QUEUE)
            }
        }
        // Every second while anyone waits: who's vanished may have changed, and so who's counted.
        if (changed || queue.isNotEmpty()) publish()
        history.prune(now)
    }

    // Higher priorities first; a stable sort keeps the order players joined the line in.
    private fun reorder() {
        val sorted = queue.sortedByDescending { plugin.priority(it) }
        queue.clear()
        queue.addAll(sorted)
    }

    // A snapshot for the action bar and the API, which read it without touching the queue.
    private fun publish() {
        if (queue.isEmpty()) {
            positions = emptyMap()
            shown = Shown.NONE
            return
        }
        val all = HashMap<PlayerRef, Int>(queue.size * 2)
        val visible = HashMap<PlayerRef, Int>(queue.size * 2)
        var place = 0
        var seen = 0
        for (player in queue) {
            all[player] = ++place
            // A vanished player sees their own place among those everyone can see, and isn't counted for anyone else.
            if (plugin.server.vanishedFrom(null, player)) visible[player] = seen + 1 else visible[player] = ++seen
        }
        positions = all
        shown = Shown(visible, seen)
    }

    /** The line as players see it: each one's place, and how many everyone can see. */
    internal class Shown(val places: Map<PlayerRef, Int>, val total: Int) {
        fun total(player: PlayerRef, vanished: Boolean): Int = if (vanished) total + 1 else total

        companion object {
            val NONE = Shown(emptyMap(), 0)
        }
    }

    internal companion object {
        /** Arrivals while the main server is up wait this long, in case they were sent here on purpose. */
        const val GRACE_MILLIS: Long = 5_000
    }
}
