package co.xenastudios.xbans

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.i18n.Translations
import co.xenastudios.core.common.proxy.Peer
import co.xenastudios.core.common.proxy.ProxyChannel
import co.xenastudios.core.common.proxy.ProxyPayload
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.later
import co.xenastudios.core.common.text.Component
import co.xenastudios.xbans.api.Alt
import co.xenastudios.xbans.api.PunishRequest
import co.xenastudios.xbans.api.PunishResult
import co.xenastudios.xbans.api.Punishment
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.api.XBans
import co.xenastudios.xbans.config.XBansConfig
import co.xenastudios.xbans.model.Entry
import co.xenastudios.xbans.model.Length
import co.xenastudios.xbans.store.alts
import co.xenastudios.xbans.store.find
import co.xenastudios.xbans.store.seen
import kotlin.time.Duration.Companion.seconds

/**
 * What [XBansPlugin] does past startup: the API's calls, telling staff and the proxy, and a muted
 * player's commands, apart so this class loads with the first of them rather than at enable
 * (decisions/2026-11-25-a-lighter-core.md).
 */
internal object Calls {
    fun tellStaff(plugin: XBansPlugin, name: String, ban: Entry) {
        val now = plugin.now()
        plugin.platform.scheduler.run(Where.Global) { _ ->
            for (staff in plugin.server.online()) {
                if (plugin.server.hasPermission(staff, XBansPlugin.NOTIFY_BANNED_JOIN)) {
                    plugin.texts.about(staff, XBansMessages.bannedJoin, ban, now, "player" to name)?.let { plugin.server.send(staff, it) }
                }
            }
        }
    }

    fun mutedMessage(plugin: XBansPlugin, player: PlayerRef, mute: Entry): Component? =
        plugin.texts.about(player, if (mute.punishment.isPermanent) XBansMessages.muted else XBansMessages.tempMuted, mute, plugin.now())

    fun blocksCommand(plugin: XBansPlugin, line: String): Boolean {
        val text = line.removePrefix("/")
        val label = text.substringBefore(' ').lowercase()
        if (label.isEmpty()) return false
        val names = blocked(plugin)
        if (strip(label) in names) return true
        val known = plugin.server.commandNames(label) ?: return false
        return known.any { strip(it.lowercase()) in names }
    }

    private fun blocked(plugin: XBansPlugin): Set<String> {
        val current = plugin.config[XBansConfig.mutes.blockedCommands]
        if (current !== plugin.blockedFrom) {
            plugin.blockedNames = current.map { strip(it.trim().lowercase().removePrefix("/")) }.toSet()
            plugin.blockedFrom = current
        }
        return plugin.blockedNames
    }

    private fun strip(label: String): String = label.substringAfterLast(':')

    fun tellProxy(plugin: XBansPlugin, entry: Entry) {
        val channel = plugin.channel ?: return
        if (plugin.server.proxy || entry.type != PunishmentType.BAN && entry.type != PunishmentType.KICK) return
        // NO_PLAYER (nobody here to carry it) is fine: the change feed brings it within check-every.
        channel.send(Peer.Proxy, XBansPlugin.TOPIC_ENFORCE, ProxyPayload.write { int(1); long(entry.id) })
    }

    fun listen(plugin: XBansPlugin, channel: ProxyChannel) {
        if (!plugin.server.proxy) return
        channel.listen(XBansPlugin.TOPIC_ENFORCE) { message ->
            val id = ProxyPayload.read(message.payload) { if (int() == 1) long() else null } ?: return@listen
            val backend = plugin.backend ?: return@listen
            backend.find(id).onSettled { found, _ ->
                val entry = (found as? Outcome.Ok)?.value ?: return@onSettled
                if (entry.punishment.removal != null || !plugin.markHeard(entry.id)) return@onSettled
                plugin.punisher.applyMade(entry, emptyList(), false, false, null)
            }
        }
    }

    fun activeOf(plugin: XBansPlugin, player: PlayerRef, mute: Boolean): Punishment? {
        val backend = plugin.backend ?: return null
        val now = plugin.now()
        val server = plugin.loginServer(player)
        val entry = if (mute) backend.active.mute(player, plugin.sessions.ip(player), server, now) else backend.active.ban(player, null, server, now)
        return entry?.punishment
    }

    fun punish(plugin: XBansPlugin, request: PunishRequest): Pending<PunishResult> {
        val name = plugin.server.name(request.target) ?: plugin.cache?.get(request.target)?.name ?: request.target.toString()
        val made = Punisher.Request(
            request.type, request.target, name, null, request.ipBased,
            if (request.durationMillis <= 0) Length.PERMANENT else Length(request.durationMillis), request.reason, null, request.scope,
            request.silent, request.extraSilent, request.staff, request.staffName, null, null,
        )
        return result(plugin, plugin.punisher.punish(made))
    }

    fun pardon(plugin: XBansPlugin, player: PlayerRef, type: PunishmentType, staff: PlayerRef?, staffName: String, reason: String): Pending<PunishResult> {
        val name = plugin.server.name(player) ?: plugin.cache?.get(player)?.name ?: player.toString()
        return result(plugin, plugin.punisher.revoke(Punisher.Revoke(type, player, name, null, null, null, reason, false, false, staff, staffName, null, null)))
    }

    private fun result(plugin: XBansPlugin, done: Pending<Done>): Pending<PunishResult> {
        val out = newPending<PunishResult>()
        done.onSettled { value, error ->
            out.resolve(when (value) {
                is Done.Made -> PunishResult.done(value.entry.punishment)
                is Done.Lifted -> PunishResult.done(value.entries.first().punishment)
                is Done.Refused -> PunishResult(PunishResult.Outcome.REFUSED, null, refusal(plugin, value))
                Done.Cancelled -> PunishResult.CANCELLED
                Done.NotReady -> PunishResult(PunishResult.Outcome.FAILED, null, "xBans is still opening its database.")
                is Done.Failed -> PunishResult(PunishResult.Outcome.FAILED, null, value.error)
                null -> PunishResult(PunishResult.Outcome.FAILED, null, error.toString())
            })
        }
        return out
    }

    fun refusal(plugin: XBansPlugin, refused: Done.Refused): String {
        val english = Translations.of(XBansMessages.catalog, emptyMap())
        val language = english.default
        val placeholders = refused.placeholders + (refused.entry?.let { plugin.texts.of(it, plugin.now()) } ?: emptyList())
        return plugin.texts.render(language, refused.message, placeholders)?.plain() ?: refused.message.key
    }

    fun alts(plugin: XBansPlugin, player: PlayerRef): Pending<List<Alt>> {
        val backend = plugin.backend ?: return pendingOf(emptyList())
        val out = newPending<List<Alt>>()
        backend.alts(player, null).onSettled { found, _ ->
            out.resolve((found as? Outcome.Ok)?.value.orEmpty().mapNotNull { use ->
                use.id?.takeIf { it != player }?.let { Alt(it, use.name, kotlin.time.Instant.fromEpochMilliseconds(use.last)) }
            })
        }
        return out
    }

    fun isStaff(plugin: XBansPlugin, player: PlayerRef): Boolean {
        val server = plugin.server
        if (!server.isOnline(player)) return false
        if (server.hasPermission(player, XBans.STAFF_PERMISSION)) return true
        val c = XBansConfig.commands
        val config = plugin.config
        return listOf(c.ban, c.mute, c.kick, c.warn).any { config[it.enabled] && config[it.permission].let { node -> node.isNotEmpty() && server.hasPermission(player, node) } }
    }

    fun joined(plugin: XBansPlugin, player: PlayerRef, name: String, address: String?): Entry? {
        val backend = plugin.backend
        val ip = if (backend == null || !plugin.config[XBansConfig.ips.enabled]) null else backend.keys.keyOf(address)
        val sharing = plugin.sessions.joined(player, ip)
        if (sharing >= XBansPlugin.SHARED_IP_WARNING && !plugin.warnedShared) {
            plugin.warnedShared = true
            plugin.log.warn("$sharing players online are on the same IP. If they come through a proxy, it isn't forwarding their real addresses, and an IP ban " +
                "would shut them all out: turn on the proxy's forwarding (Velocity's player-info-forwarding, or BungeeCord's ip_forward).")
        }
        if (backend == null) return null
        val ban = backend.active.ban(player, ip, plugin.loginServer(player), plugin.now())
        if (ban != null) return ban
        if (backend.database.available) {
            backend.seen(player, name, ip, plugin.server.weight(player), plugin.server.hasPermission(player, co.xenastudios.xbans.Power.EXEMPT), plugin.now()).onSettled { done, error ->
                if (done !is Outcome.Ok) plugin.log.warn("Couldn't record $name's join: ${(done as? Outcome.Err)?.error ?: error}")
            }
        }
        if (ip != null && plugin.config[XBansConfig.ips.altAlerts]) plugin.actions().alertOnJoin(player, name, ip)
        return null
    }

    fun denied(plugin: XBansPlugin, player: PlayerRef, name: String, ban: Entry?): Component {
        if (ban != null) tellStaff(plugin, name, ban)
        plugin.punisher.loginDenied(player, name, ban)
        return if (ban == null) plugin.texts.render(player, XBansMessages.screenNotReady) ?: Component.text("The server is still starting.")
        else plugin.punisher.screen(player, ban)
    }

    fun failedToOpen(plugin: XBansPlugin, problem: String, config: Config, ticket: Int) {
        if (!plugin.current(ticket)) return
        // Said once: a retry every 15 seconds that fails the same way adds nothing to the plugin.log.
        val repeat = problem == plugin.lastProblem
        plugin.lastProblem = problem
        plugin.failure = problem
        if (!repeat) plugin.log.error(problem)
        val current = plugin.backend
        if (current != null) {
            plugin.log.error("Still using ${current.database.description}.")
            plugin.wanted = null // So reloading with the same settings tries again.
            return
        }
        if (!repeat) plugin.log.error("Nobody can join until it opens: trying again every ${XBansPlugin.RETRY.inWholeSeconds}s.")
        plugin.platform.scheduler.later(Where.Async, XBansPlugin.RETRY, plugin.retryTask(config, ticket))
    }

    fun stop(plugin: XBansPlugin): Pending<Unit> {
        plugin.running = false
        XBans.register(null)
        plugin.updates?.close()
        plugin.updates = null
        plugin.modules.clear()
        plugin.registrar?.clear()
        plugin.permissionRegistrar?.sync(emptyList())
        plugin.channel?.close()
        val current = plugin.backend ?: return pendingOf(Unit)
        plugin.backend = null
        current.feed?.close()
        return current.database.close()
    }
}
