package co.xenastudios.xbans

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.xbans.api.Punishment
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.api.Removal
import co.xenastudios.xbans.api.event.PardonEvent
import co.xenastudios.xbans.api.event.PardonedEvent
import co.xenastudios.xbans.api.event.PunishEvent
import co.xenastudios.xbans.api.event.PunishedEvent
import co.xenastudios.xbans.api.event.StaffNotifyEvent
import co.xenastudios.xbans.config.XBansConfig
import co.xenastudios.xbans.model.Entry
import co.xenastudios.xbans.model.Length
import co.xenastudios.xbans.model.Replacing
import co.xenastudios.xbans.store.Backend
import kotlin.time.Instant

/** How a punishment or a pardon went: what the command's sender, or the API's caller, is told. */
public sealed class Done {
    public class Made(public val entry: Entry) : Done()

    public class Lifted(public val entries: List<Entry>) : Done()

    /** It can't go ahead; [message] says why, about [entry] when there is one. Not a bug. */
    public class Refused(public val message: MessageKey, public val placeholders: List<Pair<String, Any>> = emptyList(), public val entry: Entry? = null) : Done()

    public data object Cancelled : Done()

    public data object NotReady : Done()

    /** The database failed: [error], already logged. */
    public class Failed(public val error: String) : Done()
}

/**
 * Makes and revokes punishments, for commands, the API and the network alike: checks them, stores
 * them, then makes them felt here (what's in force in memory, kicks, the player told, the
 * broadcast, the events). 1.x's rules (decisions/2026-11-18-xbans.md), each step that needs the
 * database off the server's threads; memory changes as soon as the database has committed, before
 * anyone is kicked, so a player joining at that moment is caught by the join check.
 */
public class Punisher internal constructor(private val plugin: XBansPlugin) {

    /**
     * A punishment to make. [target] is `null` for an IP no account is known for; [ip] the IP key
     * given rather than the player's own; [length] `null` when none was given (permanent, the
     * template's, or the warnings default); [scope] `null` for the config's default; [power] the
     * sender's limits, `null` for none; [online] who was online when the command ran, read on its
     * thread, to hold the target and everyone on an IP to the sender's limits; [temporary] from
     * `/tempban` or `/tempmute`: a template step that's permanent is refused.
     */
    public class Request(
        public val type: PunishmentType,
        public val target: PlayerRef?,
        public val targetName: String,
        public val ip: String?,
        public val ipBased: Boolean,
        public val length: Length?,
        public val reason: String?,
        public val template: String?,
        public val scope: String?,
        public val silent: Boolean,
        public val extraSilent: Boolean,
        public val staff: PlayerRef?,
        public val staffName: String,
        public val power: Power?,
        public val sender: CommandSource?,
        public val templateLength: Boolean = false,
        public val online: List<OnlinePlayer> = emptyList(),
        public val temporary: Boolean = false,
    ) {
        internal fun with(length: Length?, reason: String?, ipBased: Boolean, silent: Boolean, templateLength: Boolean): Request =
            Request(type, target, targetName, ip, ipBased, length, reason, template, scope, silent, extraSilent, staff, staffName, power, sender, templateLength, online, temporary)
    }

    /** Lifting a player's [type] (all their scopes, unless [scope] names one), an IP's, or one punishment by [id]. */
    public class Revoke(
        public val type: PunishmentType,
        public val target: PlayerRef?,
        public val targetName: String,
        public val ip: String?,
        public val id: Long?,
        public val scope: String?,
        public val reason: String?,
        public val silent: Boolean,
        public val extraSilent: Boolean,
        public val staff: PlayerRef?,
        public val staffName: String,
        public val power: Power?,
        public val sender: CommandSource?,
    )

    private val config: Config get() = plugin.config

    private fun now(): Long = plugin.now()

    /** The scope a punishment gets: what was typed, else the config's default. */
    public fun scope(typed: String?): String {
        val server = config[XBansConfig.scopes.serverName]
        if (typed == null) return if (config[XBansConfig.scopes.defaultScope] == XBansConfig.DefaultScope.GLOBAL) Punishment.GLOBAL else server
        return when (typed.lowercase()) {
            "*", "global", "all" -> Punishment.GLOBAL
            "local", "this" -> server
            else -> typed
        }
    }

    /**
     * Everyone online now, with their weight and exemption, for a [Request] from a sender with
     * limits. On the command's thread, where their permissions may be read; empty without limits.
     */
    public fun online(power: Power?): List<OnlinePlayer> {
        if (power == null) return emptyList()
        val server = plugin.server
        return server.online().map { OnlinePlayer(it, server.name(it) ?: it.toString(), plugin.sessions.ip(it), server.weight(it), server.hasPermission(it, Power.EXEMPT)) }
    }

    // ---- punishing ----

    /** Checks, stores and applies a punishment. */
    public fun punish(request: Request): Pending<Done> {
        val result = newPending<Done>()
        punish(request, true, result)
        return result
    }

    // first: false when trying again after a conflict, which has already asked PunishEvent's listeners.
    private fun punish(request: Request, first: Boolean, result: Pending<Done>) {
        val backend = plugin.backend ?: return done(result, Done.NotReady)
        val target = request.target
        val seen = target?.let { id -> request.online.firstOrNull { it.id == id } }
        val known: Pending<Outcome<Backend.Known, DbError>> = when {
            target == null -> pendingOf(Outcome.Ok(Backend.Known(null, 0, false, null)))
            seen != null -> pendingOf(Outcome.Ok(Backend.Known(seen.name, seen.weight, seen.exempt, seen.ip)))
            // No limits to hold them to, so their permissions aren't needed.
            plugin.server.isOnline(target) && request.power == null ->
                pendingOf(Outcome.Ok(Backend.Known(plugin.server.name(target), 0, false, plugin.sessions.ip(target))))
            else -> backend.known(target)
        }
        known.onSettled { read, error ->
            val about = (read as? Outcome.Ok)?.value ?: return@onSettled failed(result, read, error, "read who ${request.targetName} is")
            withTemplate(backend, request) { resolved ->
                if (resolved == null) return@withTemplate failed(result, null, null, "read ${request.targetName}'s history")
                val ip = if (resolved.ipBased) resolved.ip ?: about.lastIp else null
                // An IP punishment reaches every account on the IP, so each is held to the sender's limits.
                val sharing: Pending<Outcome<List<Backend.Account>, DbError>> =
                    if (ip == null || resolved.power == null) pendingOf(Outcome.Ok(emptyList())) else backend.accountsOn(ip)
                sharing.onSettled { accounts, failure ->
                    val shared = (accounts as? Outcome.Ok)?.value ?: return@onSettled failed(result, accounts, failure, "read who else is on that IP")
                    // Off the server's threads, and outside any transaction: PunishEvent's listeners may call the API.
                    plugin.platform.scheduler.run(Where.Async) { _ ->
                        when (val prepared = prepare(backend, resolved, about, ip, shared, first)) {
                            is Prepared.No -> done(result, prepared.done)
                            is Prepared.Yes -> store(backend, request, prepared, first, result)
                        }
                    }
                }
            }
        }
    }

    private fun store(backend: Backend, request: Request, prepared: Prepared.Yes, first: Boolean, result: Pending<Done>) {
        val payload = if (request.extraSilent) Backend.EXTRA_SILENT else ""
        backend.insert(prepared.entry, prepared.replaces, prepared.replacement, config[XBansConfig.scopes.serverName], payload).onSettled { stored, error ->
            if (stored is Outcome.Ok) {
                applyMade(stored.value.entry, prepared.replaces.map { it.id }, true, request.extraSilent, request.sender, stored.value.warnings)
                return@onSettled done(result, Done.Made(stored.value.entry))
            }
            val detail = (stored as? Outcome.Err)?.error?.detail.orEmpty()
            if (detail.startsWith(Backend.CONFLICT)) {
                // What it replaced was revoked meanwhile, maybe on a server whose change hasn't
                // reached this one yet: forget it, and decide again from what's in force now.
                if (first) {
                    detail.removePrefix(Backend.CONFLICT).toLongOrNull()?.let { backend.active.remove(it) }
                    return@onSettled punish(request, false, result)
                }
                return@onSettled done(result, Done.Refused(
                    if (request.type == PunishmentType.BAN) XBansMessages.alreadyBanned else XBansMessages.alreadyMuted,
                    listOf("player" to request.targetName, "expires" to "?"),
                ))
            }
            failed(result, stored, error, "save the punishment of ${request.targetName}")
        }
    }

    // The template's step, length, reason and flags, if one was named: `null` if the history couldn't be read.
    private fun withTemplate(backend: Backend, request: Request, next: (Request?) -> Unit) {
        val name = request.template ?: return next(request)
        val template = config[XBansConfig.templates][name] ?: return next(request)
        val target = request.target
        // A bare IP has no player whose history counts the step, so it starts the ladder.
        val steps: Pending<Outcome<Int, DbError>> = if (target == null) pendingOf(Outcome.Ok(0))
        else backend.ladderStep(target, name, if (template.expireLadder.permanent) 0 else now() - template.expireLadder.millis)
        steps.onSettled { step, _ ->
            val count = (step as? Outcome.Ok)?.value ?: return@onSettled next(null)
            val ladder = template.ladder.ifEmpty { listOf(Length.PERMANENT) }
            next(request.with(
                length = request.length ?: ladder[minOf(count, ladder.size - 1)],
                reason = request.reason ?: template.reason,
                ipBased = request.ipBased || template.ip,
                silent = request.silent || template.silent,
                templateLength = request.length == null,
            ))
        }
    }

    /** A punishment checked and ready to store, with the bans or mutes it replaces; or why not. */
    private sealed class Prepared {
        class Yes(val entry: Entry, val replaces: List<Entry>, val replacement: Removal?) : Prepared()

        class No(val done: Done) : Prepared()
    }

    private fun prepare(backend: Backend, request: Request, target: Backend.Known, ip: String?, sharing: List<Backend.Account>, ask: Boolean): Prepared {
        val type = request.type
        val power = request.power
        val name = if (target.name != null && request.target != null) target.name else request.targetName
        fun refuse(message: MessageKey, vararg placeholders: Pair<String, Any>) = Prepared.No(Done.Refused(message, placeholders.toList()))
        if (power != null) {
            if (target.exempt) return refuse(XBansMessages.exempt, "player" to name)
            if (outranks(power, target.weight)) return refuse(XBansMessages.outranks, "player" to name)
            if (ip != null) limitsOnIp(power, ip, sharing, request.online)?.let { return it }
        }
        var length = request.length
        if (type == PunishmentType.WARN && length == null) length = config[XBansConfig.warnings.defaultDuration]
        if (length == null || type == PunishmentType.KICK) length = Length.PERMANENT
        // /tempban with a template: its ladder's step here is permanent.
        if (request.temporary && length.permanent) return refuse(XBansMessages.tempPermanent, "template" to (request.template ?: "?"))
        if (power != null && !(request.templateLength && power.bypassTemplates)) {
            val cap = power.cap(type)
            if (cap != null && length > cap) return refuse(XBansMessages.overLimit, "max" to cap.shown())
        }
        if (request.ipBased && ip == null) return refuse(XBansMessages.noIp, "player" to name)
        val now = now()
        val scope = if (type == PunishmentType.KICK) Punishment.GLOBAL else scope(request.scope)
        val expires = if (length.permanent) null else now + length.millis
        val replaces = ArrayList<Entry>()
        if (type == PunishmentType.BAN || type == PunishmentType.MUTE) {
            val newIp = if (request.ipBased) ip else null
            for (old in backend.active.onScope(type, request.target, newIp, scope, now)) {
                when (Replacing.of(old, request.target, newIp, expires, mayLift(power, request.staff, old))) {
                    Replacing.REPLACE -> replaces += old
                    Replacing.REFUSE_LONGER -> return Prepared.No(Done.Refused(
                        if (type == PunishmentType.BAN) XBansMessages.alreadyBanned else XBansMessages.alreadyMuted, entry = old,
                    ))
                    Replacing.REFUSE_OUTRANKED -> return Prepared.No(Done.Refused(XBansMessages.cantLift, entry = old))
                    Replacing.BOTH -> Unit
                }
            }
        }
        val reason = request.reason?.takeIf { it.isNotBlank() } ?: config[XBansConfig.punishing.defaultReason]
        val draft = Punishment(
            0, type, request.target, name, request.ipBased, reason, request.staff, request.staffName, Instant.fromEpochMilliseconds(now),
            expires?.let { Instant.fromEpochMilliseconds(it) }, scope, request.silent || request.extraSilent, request.template, null,
        )
        val entry = Entry(draft, ip, power?.weight ?: Entry.CONSOLE)
        if (ask && plugin.hasListeners(PunishEvent.TYPE)) {
            val event = PunishEvent(type, draft.target, name, reason, length.millis, draft.staff, draft.staffName, draft.silent, draft.ipBased, scope)
            plugin.bus.fire(PunishEvent.TYPE, event)
            if (event.isCancelled) return Prepared.No(Done.Cancelled)
        }
        val replacement = if (replaces.isEmpty()) null else Removal(request.staff, request.staffName, Instant.fromEpochMilliseconds(now), Backend.REPLACED_REASON)
        return Prepared.Yes(entry, replaces, replacement)
    }

    private fun outranks(power: Power, weight: Int): Boolean = config[XBansConfig.staffLimits.weights] && weight > 0 && power.weight <= weight

    // Every account seen on the IP, and everyone online on it now (their permissions as they are),
    // held to exemptions and weights as the target is.
    private fun limitsOnIp(power: Power, ip: String, sharing: List<Backend.Account>, online: List<OnlinePlayer>): Prepared.No? {
        val accounts = LinkedHashMap<PlayerRef, Backend.Account>()
        for (account in sharing) accounts[account.id] = account
        for (player in online) if (player.ip == ip) accounts[player.id] = Backend.Account(player.id, player.name, player.weight, player.exempt)
        for (account in accounts.values) {
            if (account.exempt) return Prepared.No(Done.Refused(XBansMessages.exempt, listOf("player" to account.name)))
            if (outranks(power, account.weight)) return Prepared.No(Done.Refused(XBansMessages.outranks, listOf("player" to account.name)))
        }
        return null
    }

    // Staff lift their own punishments, and those made by staff whose weight was no higher than
    // theirs; the console's is highest. xbans.unban.override, the console and the API lift anything.
    // The same decides whether a new punishment may replace an old one.
    private fun mayLift(power: Power?, staff: PlayerRef?, entry: Entry): Boolean {
        if (power == null || power.override || !config[XBansConfig.staffLimits.weights]) return true
        val madeBy = entry.punishment.staff
        return madeBy != null && madeBy == staff || power.weight >= entry.staffWeight
    }

    /**
     * Enforces a ban this server didn't hear of when it was made (an import, or one caught up on
     * from the network): its players here are kicked. Nothing is broadcast.
     */
    public fun enforce(entry: Entry) {
        if (entry.type != PunishmentType.BAN || !entry.active(now())) return
        for (player in affected(entry)) if (appliesTo(entry, player)) kick(player, screen(player, entry))
    }

    /**
     * Makes a stored punishment felt here: what's in force in memory, kicks, telling the player,
     * the broadcast and the event. For this server's punishments and the network's ([local] false).
     */
    public fun applyMade(entry: Entry, replaced: List<Long>, local: Boolean, extraSilent: Boolean, sender: CommandSource?, warnings: Int = 0) {
        val backend = plugin.backend ?: return
        for (id in replaced) backend.active.remove(id)
        // In memory before anyone is kicked: a player joining now is caught by the join check.
        backend.active.add(entry)
        val p = entry.punishment
        val now = now()
        val tell = plugin.tellsPlayers
        for (player in affected(entry)) {
            if (!appliesTo(entry, player)) continue
            when (p.type) {
                PunishmentType.BAN -> kick(player, screen(player, entry))
                PunishmentType.KICK -> kick(player, plugin.texts.about(player, XBansMessages.screenKick, entry, now) ?: co.xenastudios.core.common.text.Component.EMPTY)
                PunishmentType.MUTE -> if (tell) plugin.texts.about(player, XBansMessages.youWereMuted, entry, now)?.let { plugin.server.send(player, it) }
                PunishmentType.WARN -> if (tell) plugin.texts.about(player, XBansMessages.youWereWarned, entry, now)?.let { plugin.server.send(player, it) }
            }
        }
        val message = when (p.type) {
            PunishmentType.BAN -> if (p.ipBased) XBansMessages.broadcastIpBan else XBansMessages.broadcastBan
            PunishmentType.MUTE -> if (p.ipBased) XBansMessages.broadcastIpMute else XBansMessages.broadcastMute
            PunishmentType.WARN -> XBansMessages.broadcastWarn
            PunishmentType.KICK -> XBansMessages.broadcastKick
        }
        broadcast(message, entry, emptyList(), p.silent, extraSilent, sender)
        if (local && p.type == PunishmentType.WARN && p.target != null) warnActions(p.targetName, warnings)
        if (local) plugin.tellProxy(entry)
        if (plugin.hasListeners(PunishedEvent.TYPE)) {
            val event = PunishedEvent(p, !local)
            plugin.platform.scheduler.run(Where.Async) { plugin.bus.fire(PunishedEvent.TYPE, event) }
        }
    }

    /** The screen a banned player sees, in their language: the ban, temporary ban or IP ban screen. */
    public fun screen(player: PlayerRef?, ban: Entry): co.xenastudios.core.common.text.Component {
        val message = when {
            ban.punishment.ipBased -> XBansMessages.screenIpBan
            ban.punishment.isPermanent -> XBansMessages.screenBan
            else -> XBansMessages.screenTempBan
        }
        return plugin.texts.about(player, message, ban, now()) ?: co.xenastudios.core.common.text.Component.EMPTY
    }

    // The online players a punishment is for: its account, and with an IP punishment everyone on that IP.
    private fun affected(entry: Entry): List<PlayerRef> {
        val target = entry.punishment.target
        val ip = if (entry.punishment.ipBased) entry.ip else null
        return plugin.server.online().filter { it == target || ip != null && ip == plugin.sessions.ip(it) }
    }

    // Whether it reaches [player] where they are: on this server its scope, on the proxy the server they're on.
    private fun appliesTo(entry: Entry, player: PlayerRef): Boolean = plugin.appliesHere(entry, player)

    private fun kick(player: PlayerRef, screen: co.xenastudios.core.common.text.Component) {
        plugin.sessions.quietQuit(player)
        plugin.server.kick(player, screen, true)
    }

    // The action for the count of warnings the player has now, counted when this one was stored.
    private fun warnActions(name: String, warnings: Int) {
        val action = config[XBansConfig.warnings.actions][warnings.toString()] ?: return
        if (action.commands.isEmpty()) return
        // On the global thread, outside any command that's running.
        plugin.platform.scheduler.run(Where.Global) { _ ->
            for (command in action.commands) plugin.server.dispatch(command.replace("<player>", name).trim().removePrefix("/"))
        }
    }

    // ---- revoking ----

    /** Revokes what [request] names. Refused when there's nothing to revoke, or nothing the sender may lift. */
    public fun revoke(request: Revoke): Pending<Done> {
        val result = newPending<Done>()
        val backend = plugin.backend ?: return pendingOf(Done.NotReady)
        candidates(backend, request).onSettled { read, error ->
            val found = (read as? Outcome.Ok)?.value ?: return@onSettled failed(result, read, error, "read what's in force on ${request.targetName}")
            if (found.isEmpty()) {
                if (request.id != null) return@onSettled done(result, Done.Refused(XBansMessages.noSuchId, listOf("id" to request.id.toString())))
                val message = when (request.type) {
                    PunishmentType.BAN -> XBansMessages.notBanned
                    PunishmentType.MUTE -> XBansMessages.notMuted
                    else -> XBansMessages.noWarnings
                }
                return@onSettled done(result, Done.Refused(message, listOf("player" to request.targetName)))
            }
            var allowed = found.filter { mayLift(request.power, request.staff, it) }
            if (allowed.isEmpty()) return@onSettled done(result, Done.Refused(XBansMessages.cantLift, entry = found[0]))
            // The newest warning they may lift.
            if (request.type == PunishmentType.WARN && request.id == null) allowed = listOf(allowed[0])
            plugin.platform.scheduler.run(Where.Async) { _ ->
                val removal = Removal(request.staff, request.staffName, Instant.fromEpochMilliseconds(now()), request.reason ?: "")
                if (plugin.hasListeners(PardonEvent.TYPE)) {
                    allowed = allowed.filter { entry ->
                        val event = PardonEvent(entry.punishment, request.staff, request.staffName, removal.reason)
                        plugin.bus.fire(PardonEvent.TYPE, event)
                        !event.isCancelled
                    }
                    if (allowed.isEmpty()) return@run done(result, Done.Cancelled)
                }
                val payload = if (request.extraSilent) Backend.EXTRA_SILENT else if (request.silent) Backend.SILENT else ""
                revokeEach(backend, allowed, 0, removal, payload, ArrayList(), request, result)
            }
        }
        return result
    }

    // One at a time, in order, then the first announced.
    private fun revokeEach(backend: Backend, each: List<Entry>, index: Int, removal: Removal, payload: String, revoked: MutableList<Entry>, request: Revoke, result: Pending<Done>) {
        if (index == each.size) {
            for ((i, entry) in revoked.withIndex()) applyRevoked(entry, true, request.silent, request.extraSilent, i == 0, request.sender)
            return done(result, if (revoked.isEmpty()) Done.Refused(XBansMessages.noSuchId, listOf("id" to each[0].id.toString())) else Done.Lifted(revoked))
        }
        val entry = each[index]
        backend.revoke(entry.id, removal, payload).onSettled { done, error ->
            if (done !is Outcome.Ok) return@onSettled failed(result, done, error, "save the revocation of #${entry.id}")
            if (done.value) revoked += entry.revoked(removal)
            revokeEach(backend, each, index + 1, removal, payload, revoked, request, result)
        }
    }

    // From the database, not memory: what's in force there is what can be revoked, even on a
    // server that missed a change.
    private fun candidates(backend: Backend, request: Revoke): Pending<Outcome<List<Entry>, DbError>> {
        val now = now()
        val type = request.type
        if (request.id != null) {
            val out = newPending<Outcome<List<Entry>, DbError>>()
            backend.find(request.id).onSettled { found, _ ->
                out.resolve(when (found) {
                    is Outcome.Ok -> Outcome.Ok(listOfNotNull(found.value?.takeIf { it.type == type && it.active(now) }))
                    is Outcome.Err -> found
                    null -> Outcome.Err(DbError(DbError.Kind.OTHER, "the database failed"))
                })
            }
            return out
        }
        if (type == PunishmentType.WARN) return backend.activeWarnings(request.target ?: return pendingOf(Outcome.Ok(emptyList())), now)
        val scope = request.scope?.let(::scope)
        val out = newPending<Outcome<List<Entry>, DbError>>()
        backend.inForceOf(type, request.target, request.ip, now).onSettled { found, _ ->
            out.resolve(when (found) {
                is Outcome.Ok -> Outcome.Ok(found.value.filter { scope == null || it.punishment.scope.equals(scope, true) })
                is Outcome.Err -> found
                null -> Outcome.Err(DbError(DbError.Kind.OTHER, "the database failed"))
            })
        }
        return out
    }

    /** Makes a revocation felt here. Only the first of several revoked at once is announced. */
    public fun applyRevoked(entry: Entry, local: Boolean, silent: Boolean, extraSilent: Boolean, announce: Boolean, sender: CommandSource?) {
        plugin.backend?.active?.remove(entry.id)
        val p = entry.punishment
        val target = p.target
        if (p.type == PunishmentType.MUTE && target != null && plugin.tellsPlayers && plugin.server.isOnline(target)) {
            plugin.texts.about(target, XBansMessages.youWereUnmuted, entry, now())?.let { plugin.server.send(target, it) }
        }
        if (announce) {
            val message = when (p.type) {
                PunishmentType.BAN -> XBansMessages.broadcastUnban
                PunishmentType.MUTE -> XBansMessages.broadcastUnmute
                else -> XBansMessages.broadcastUnwarn
            }
            broadcast(message, entry, listOf("staff" to (p.removal?.staffName ?: "?")), silent, extraSilent, sender)
        }
        if (plugin.hasListeners(PardonedEvent.TYPE)) {
            val event = PardonedEvent(p, !local)
            plugin.platform.scheduler.run(Where.Async) { plugin.bus.fire(PardonedEvent.TYPE, event) }
        }
    }

    // ---- telling people ----

    /**
     * Sends a broadcast on the global thread: to everyone with `xbans.notify.broadcast`, or when
     * silent to staff with `xbans.notify.silent`, or when extra silent to the console only; each
     * in their own language. The staff member who did it always sees it. On a proxy whose servers
     * share the database, the servers tell their own players, so the proxy tells only the console
     * and the sender.
     */
    public fun broadcast(message: MessageKey, entry: Entry?, more: List<Pair<String, Any>>, silent: Boolean, extraSilent: Boolean, sender: CommandSource?) {
        val now = now()
        plugin.platform.scheduler.run(Where.Global) { _ ->
            val texts = plugin.texts
            val placeholders = { language: co.xenastudios.core.common.i18n.Language -> more + (entry?.let { texts.of(it, now) } ?: emptyList()) }
            fun line(language: co.xenastudios.core.common.i18n.Language) =
                if (silent || extraSilent) texts.prefixed(language, message, XBansMessages.silentPrefix, placeholders(language))
                else texts.render(language, message, placeholders(language))
            val consoleLine = line(texts.language(null)) ?: return@run
            plugin.server.console(consoleLine)
            val recipients = ArrayList<PlayerRef>()
            if (!extraSilent && plugin.tellsPlayers) {
                val node = if (silent) NOTIFY_SILENT else NOTIFY_BROADCAST
                for (player in plugin.server.online()) if (plugin.server.hasPermission(player, node)) recipients += player
            }
            val senderPlayer = sender?.player
            if (recipients.isNotEmpty() && plugin.hasListeners(StaffNotifyEvent.TYPE)) {
                val event = StaffNotifyEvent(consoleLine.plain(), silent || extraSilent, recipients)
                plugin.bus.fire(StaffNotifyEvent.TYPE, event)
                if (event.isCancelled) recipients.clear()
            }
            val drawn = HashMap<String, co.xenastudios.core.common.text.Component?>()
            for (player in recipients) {
                val language = texts.language(player)
                val text = drawn.getOrPut(language.tag.toString()) { line(language) } ?: continue
                plugin.server.send(player, text)
            }
            if (senderPlayer != null && senderPlayer !in recipients) line(texts.language(senderPlayer))?.let(sender::reply)
        }
    }

    private fun done(result: Pending<Done>, done: Done) {
        result.resolve(done)
    }

    private fun failed(result: Pending<Done>, outcome: Any?, error: Throwable?, what: String) {
        val why = (outcome as? Outcome.Err<*>)?.error?.toString() ?: error?.toString() ?: "the database failed"
        plugin.log.error("Couldn't $what: $why")
        result.resolve(Done.Failed(why))
    }

    public companion object {
        public const val NOTIFY_BROADCAST: String = "xbans.notify.broadcast"
        public const val NOTIFY_SILENT: String = "xbans.notify.silent"
    }
}
