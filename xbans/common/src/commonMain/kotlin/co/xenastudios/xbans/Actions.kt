package co.xenastudios.xbans

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.reject
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.db.DbError
import co.xenastudios.core.common.i18n.ChatPages
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.xbans.api.HistoryPage
import co.xenastudios.xbans.api.PunishmentType
import co.xenastudios.xbans.config.XBansConfig
import co.xenastudios.xbans.ip.IpKeys
import co.xenastudios.xbans.model.Entry
import co.xenastudios.xbans.model.Length
import co.xenastudios.xbans.store.Backend
import co.xenastudios.xbans.store.activeWarnings
import co.xenastudios.xbans.store.alts
import co.xenastudios.xbans.store.boundary
import co.xenastudios.xbans.store.count
import co.xenastudios.xbans.store.find
import co.xenastudios.xbans.store.idByName
import co.xenastudios.xbans.store.ipHistory
import co.xenastudios.xbans.store.page
import co.xenastudios.xbans.store.prune

/**
 * What xBans' commands do, made the first time one runs: a server that's just started hasn't
 * loaded it. Every command that needs the database runs its query off the server's threads and
 * replies when it's back; nothing here blocks a server thread.
 */
internal class Actions(private val plugin: XBansPlugin) {
    private val texts get() = plugin.texts
    private val config get() = plugin.config

    /** Each viewer's place in each list they page through ([Cursors]). */
    val cursors: Cursors = Cursors()

    private fun reply(source: CommandSource, message: MessageKey, vararg placeholders: Pair<String, Any>) = texts.reply(source, message, *placeholders)

    /** Tells [source] how a punishment or a pardon went, unless it went (the broadcast says so). */
    fun tell(source: CommandSource, done: Done?) {
        when (done) {
            is Done.Made, is Done.Lifted -> Unit
            is Done.Refused -> {
                val placeholders = done.placeholders + (done.entry?.let { texts.of(it, plugin.now()) } ?: emptyList())
                texts.render(texts.language(source.player), done.message, placeholders)?.let(source::reply)
            }
            Done.Cancelled -> reply(source, XBansMessages.cancelled)
            Done.NotReady -> reply(source, XBansMessages.notReady)
            is Done.Failed, null -> reply(source, XBansMessages.failed)
        }
    }

    private fun usage(source: CommandSource, usage: String) = reply(source, CoreMessages.commandUsage, "usage" to usage)

    fun info(source: CommandSource) {
        val backend = plugin.backend
        val storage = backend?.database?.description ?: plugin.failure?.let { "no database (see the console)" } ?: "the database, still opening"
        reply(source, if (plugin.server.proxy) XBansMessages.proxyInfo else XBansMessages.info,
            "version" to plugin.version, "platform" to plugin.platform.name, "count" to (backend?.active?.size ?: 0), "storage" to storage)
    }

    // ---- punishing ----

    fun punish(source: CommandSource, kind: Commands.Kind, words: List<String>) {
        val lasting = kind.type != PunishmentType.KICK
        val templates = config[XBansConfig.templates]
        val parsed = Args.parse(words, lasting) { word -> templates[word]?.type == kind.type } ?: return usage(source, usage(kind))
        parsed.badLength?.let { return reply(source, XBansMessages.badDuration, "input" to it) }
        if (kind.temp && (parsed.length == null || parsed.length.permanent) && parsed.template == null) return usage(source, usage(kind))
        val perms = plugin.permissions
        if (parsed.scope != null && !source.hasPermission(perms.scope.node)) return reply(source, XBansMessages.scopeNoPermission)
        if (parsed.extraSilent && !source.hasPermission(perms.extraSilent.node)) return reply(source, XBansMessages.extraSilentNoPermission)
        parsed.template?.let { name ->
            val template = templates.getValue(name)
            if (!source.hasPermission(template.permissionFor(name))) return reply(source, XBansMessages.templateNoPermission, "template" to name)
        }
        val ip = kind.ip || parsed.ip && kind.type != PunishmentType.WARN && kind.type != PunishmentType.KICK
        if (ip && !config[XBansConfig.ips.enabled]) return reply(source, XBansMessages.ipsOff)
        if (ip && parsed.ip) {
            val node = config[if (kind.type == PunishmentType.BAN) XBansConfig.commands.ipban.permission else XBansConfig.commands.ipmute.permission]
            if (node.isNotEmpty() && !source.hasPermission(node)) return reply(source, CoreMessages.commandNoPermission)
        }
        val silent = parsed.silent || config[XBansConfig.punishing.silentByDefault] && !parsed.publicFlag
        val power = Power.of(source, config, plugin.server)
        val (staff, staffName) = plugin.staffOf(source)
        val punisher = plugin.punisher
        // Read here, on the command's thread: the target and everyone on an IP are held to the sender's limits.
        val online = punisher.online(power)
        val targetArg = if (kind.type == PunishmentType.KICK) {
            // Only someone here can be kicked.
            val found = plugin.players.find(parsed.target, source) ?: return reply(source, CoreMessages.commandUnknownPlayer, "player" to parsed.target)
            found.toString()
        } else {
            parsed.target
        }
        resolve(source, targetArg, ips = ip, ids = false) { target ->
            punisher.punish(Punisher.Request(
                kind.type, target.id, target.name, target.ip, ip || target.ip != null, parsed.length, parsed.reason, parsed.template,
                parsed.scope, silent, parsed.extraSilent, staff, staffName, power, source, online = online, temporary = kind.temp,
            )).onSettled { done, _ -> tell(source, done) }
        }
    }

    private fun usage(kind: Commands.Kind): String {
        val target = if (kind.ip) "<player|ip>" else "<player>"
        return when (kind.type) {
            PunishmentType.KICK -> "/kick <player> [-s] [reason]"
            PunishmentType.WARN -> "/warn <player> [-s] [duration] [template] [reason]"
            else -> "/${kind.name} $target [-s] " + (if (kind.temp) "<duration>" else "[duration]") + " [server:<name>] [template] [reason]"
        }
    }

    fun revoke(source: CommandSource, type: PunishmentType, words: List<String>) {
        val parsed = Args.parse(words, false) { false } ?: return
        val perms = plugin.permissions
        if (parsed.scope != null && !source.hasPermission(perms.scope.node)) return reply(source, XBansMessages.scopeNoPermission)
        if (parsed.extraSilent && !source.hasPermission(perms.extraSilent.node)) return reply(source, XBansMessages.extraSilentNoPermission)
        val silent = parsed.silent || config[XBansConfig.punishing.silentByDefault] && !parsed.publicFlag
        val power = Power.of(source, config, plugin.server)
        val (staff, staffName) = plugin.staffOf(source)
        resolve(source, parsed.target, ips = type != PunishmentType.WARN, ids = true) { target ->
            plugin.punisher.revoke(Punisher.Revoke(
                type, target.id, target.name, target.ip, target.punishment, parsed.scope, parsed.reason, silent, parsed.extraSilent,
                staff, staffName, power, source,
            )).onSettled { done, _ -> tell(source, done) }
        }
    }

    // ---- who a command names ----

    /** A player by name or uuid (online or not), an IP address, or a punishment's `#id`. */
    class Target(val id: PlayerRef?, val name: String, val ip: String?, val punishment: Long?)

    /**
     * Who [arg] names, then [next] with them; or [source] is told why not. Never blocks: names
     * in memory answer at once, others are looked up off the server's threads.
     */
    fun resolve(source: CommandSource, arg: String, ips: Boolean, ids: Boolean, next: (Target) -> Unit) {
        if (ids && arg.length > 1 && arg[0] == '#' && arg.drop(1).all { it in '0'..'9' }) {
            val id = arg.drop(1).toLongOrNull() ?: return reply(source, XBansMessages.noSuchId, "id" to arg.drop(1))
            return next(Target(null, arg, null, id))
        }
        if (IpKeys.looksLikeAddress(arg)) {
            if (!ips) return reply(source, XBansMessages.neverJoined, "player" to arg)
            if (!config[XBansConfig.ips.enabled]) return reply(source, XBansMessages.ipsOff)
            val backend = plugin.backend ?: return reply(source, XBansMessages.notReady)
            if (!IpKeys.punishable(arg)) return reply(source, XBansMessages.badIp)
            val key = backend.keys.key(arg)
            return next(Target(null, IpKeys.label(key), key, null))
        }
        PlayerRef.parseOrNull(arg)?.takeIf { arg.length == 32 || arg.length == 36 }?.let { id ->
            return next(Target(id, plugin.server.name(id) ?: plugin.cache?.get(id)?.name ?: arg, null, null))
        }
        if (!NAME.matches(arg)) return reply(source, XBansMessages.neverJoined, "player" to arg)
        val online = plugin.server.online().firstOrNull { plugin.server.name(it).equals(arg, ignoreCase = true) }
        if (online != null) return next(Target(online, plugin.server.name(online) ?: arg, null, null))
        plugin.cache?.find(arg)?.let { return next(Target(it.player, it.name, null, null)) }
        lookup(arg).onSettled { found, _ ->
            if (found != null) next(Target(found.first, found.second, null, null)) else reply(source, XBansMessages.neverJoined, "player" to arg)
        }
    }

    // A player not online: the family's cache, xBans' own players table, then the server's own lookup.
    private fun lookup(name: String): Pending<Pair<PlayerRef, String>?> {
        val out = newPending<Pair<PlayerRef, String>?>()
        val cache = plugin.cache
        val fromCache: Pending<Pair<PlayerRef, String>?> = if (cache == null) pendingOf(null) else {
            val read = newPending<Pair<PlayerRef, String>?>()
            cache.lookup(name).onSettled { found, _ -> read.resolve((found as? Outcome.Ok)?.value?.let { it.player to it.name }) }
            read
        }
        fromCache.onSettled { cached, _ ->
            if (cached != null) return@onSettled run { out.resolve(cached) }
            val backend = plugin.backend
            val own: Pending<Outcome<Pair<PlayerRef, String>?, DbError>> = backend?.idByName(name) ?: pendingOf(Outcome.Ok(null))
            own.onSettled { known, _ ->
                val mine = (known as? Outcome.Ok)?.value
                if (mine != null) return@onSettled run { out.resolve(mine) }
                plugin.server.lookup(name).onSettled { found, _ -> out.resolve(found) }
            }
        }
        return out
    }

    // ---- lookups ----

    fun history(source: CommandSource, who: String, words: List<String>, staff: Boolean) {
        var type: PunishmentType? = null
        var page = 1
        for (word in words) {
            val lower = word.lowercase()
            val named = typeWord(lower)
            when {
                named != null -> type = named
                lower.isNotEmpty() && lower.length < 7 && lower.all { it in '0'..'9' } -> page = lower.toInt()
                else -> return usage(source, "/${if (staff) "staffhistory <staff|console>" else "history <player>"} [bans|mutes|warnings|kicks] [page]")
            }
        }
        val console = staff && (who.equals("console", true) || who.equals("[console]", true))
        val label = if (staff) XBansConfig.commands.staffhistory.command else XBansConfig.commands.history.command
        val show = { found: Target ->
            val filter = if (staff) Backend.Filter.staff(found.id, type) else Backend.Filter.target(found.id!!, type)
            val command = "/$label " + (if (console) "console" else found.name) + (type?.let { " " + typeName(it) } ?: "") + " {page}"
            val viewer = source.player?.toString() ?: source.name
            cursors.page(plugin.backend, viewer, filter, page, config[XBansConfig.lists.perPage]).onSettled { result, _ ->
                val shown = (result as? Outcome.Ok)?.value ?: return@onSettled reply(source, if (plugin.backend == null) XBansMessages.notReady else XBansMessages.failed)
                sendPage(source, shown, if (staff) XBansMessages.staffHistoryHeader else XBansMessages.historyHeader, found.name, command)
            }
        }
        if (console) show(Target(null, "Console", null, null)) else resolve(source, who, ips = false, ids = false, next = show)
    }

    /** A page of punishments: the header, a line each, and the footer. */
    private fun sendPage(source: CommandSource, page: ChatPages.Page<Entry>, header: MessageKey, who: String, command: String) {
        if (page.items.isEmpty()) return reply(source, XBansMessages.historyEmpty)
        reply(source, header, "player" to who, "count" to page.total)
        val now = plugin.now()
        for (entry in page.items) texts.about(source.player, XBansMessages.historyLine, entry, now)?.let(source::reply)
        footer(source, page, command)
    }

    private fun footer(source: CommandSource, page: ChatPages.Page<*>, command: String) {
        if (page.pages <= 1) return
        val language = texts.language(source.player)
        val placeholders = ChatPages.footer(page, command, language).map { it.key to (it.value as Any) }
        texts.render(language, XBansMessages.footer, placeholders)?.let(source::reply)
    }

    /** One page of a history for the API, newest first. */
    fun historyPage(filter: Backend.Filter, page: Int, perPage: Int): Pending<HistoryPage> {
        val out = newPending<HistoryPage>()
        cursors.page(plugin.backend, "api", filter, page, perPage).onSettled { result, error ->
            when (result) {
                is Outcome.Ok -> out.resolve(HistoryPage(result.value.items.map { it.punishment }, result.value.number, result.value.pages, result.value.total))
                else -> out.reject(IllegalStateException("couldn't read the history: ${(result as? Outcome.Err)?.error ?: error}"))
            }
        }
        return out
    }

    fun check(source: CommandSource, who: String, type: PunishmentType) {
        resolve(source, who, ips = true, ids = true) { target ->
            val show = { found: List<Entry> ->
                if (found.isEmpty()) reply(source, XBansMessages.checkNone, "player" to who)
                val now = plugin.now()
                for (entry in found) texts.about(source.player, XBansMessages.check, entry, now)?.let(source::reply)
            }
            // In force now: from memory by player or IP; by id from the database, so an ended one shows too.
            val id = target.punishment
            if (id != null) {
                val backend = plugin.backend ?: return@resolve reply(source, XBansMessages.notReady)
                backend.find(id).onSettled { found, _ -> show(listOfNotNull((found as? Outcome.Ok)?.value?.takeIf { it.type == type })) }
            } else {
                val backend = plugin.backend ?: return@resolve reply(source, XBansMessages.notReady)
                show(backend.active.onScope(type, target.id, target.ip, null, plugin.now()).sortedByDescending { it.id })
            }
        }
    }

    fun warnings(source: CommandSource, first: String?, second: Int?) {
        var who = first
        var page = second ?: 1
        val others = plugin.permissions.warningsOthers.node
        // A lone number is a page of your own, unless it's the name of a player you may look up (names
        // can be all digits), or the console asked, which has no warnings of its own.
        if (who != null && second == null && who.length < 7 && who.all { it in '0'..'9' } && source.player != null &&
            !(source.hasPermission(others) && plugin.cache?.find(who) != null)) {
            page = who.toInt()
            who = null
        }
        val self = source.player
        val show = { target: Target ->
            val backend = plugin.backend
            if (backend == null) reply(source, XBansMessages.notReady)
            else backend.activeWarnings(target.id!!, plugin.now()).onSettled { found, _ ->
                val warnings = (found as? Outcome.Ok)?.value ?: return@onSettled reply(source, XBansMessages.failed)
                if (warnings.isEmpty()) return@onSettled reply(source, XBansMessages.warningsNone, "player" to target.name)
                val shown = ChatPages.of(warnings, page, config[XBansConfig.lists.perPage])
                reply(source, XBansMessages.warningsHeader, "player" to target.name, "count" to warnings.size)
                val now = plugin.now()
                for (entry in shown.items) texts.about(source.player, XBansMessages.historyLine, entry, now)?.let(source::reply)
                footer(source, shown, "/${XBansConfig.commands.warnings.command} ${target.name} {page}")
            }
        }
        if (who == null || self != null && plugin.server.name(self).equals(who, ignoreCase = true)) {
            if (self == null) return reply(source, CoreMessages.commandPlayersOnly)
            show(Target(self, plugin.server.name(self) ?: source.name, null, null))
        } else {
            if (!source.hasPermission(others)) return reply(source, CoreMessages.commandNoPermission)
            resolve(source, who, ips = false, ids = false, next = show)
        }
    }

    fun list(source: CommandSource, type: PunishmentType, page: Int) {
        val backend = plugin.backend ?: return reply(source, XBansMessages.notReady)
        // Everyone banned, sorted: off the server's threads.
        plugin.platform.scheduler.run(Where.Async) { _ ->
            val now = plugin.now()
            val all = backend.active.all(type, if (plugin.server.proxy) null else plugin.serverName, now)
            if (all.isEmpty()) return@run reply(source, XBansMessages.listEmpty)
            val shown = ChatPages.of(all, page, config[XBansConfig.lists.perPage])
            reply(source, if (type == PunishmentType.BAN) XBansMessages.banlistHeader else XBansMessages.mutelistHeader, "count" to all.size)
            for (entry in shown.items) texts.about(source.player, XBansMessages.historyLine, entry, now)?.let(source::reply)
            val name = if (type == PunishmentType.BAN) XBansConfig.commands.banlist.command else XBansConfig.commands.mutelist.command
            footer(source, shown, "/$name {page}")
        }
    }

    fun prune(source: CommandSource, who: String, words: List<String>) {
        var since = 0L
        var confirm = false
        for (word in words) {
            if (word.equals("confirm", true)) {
                confirm = true
                continue
            }
            val length = Length.parse(word) ?: return reply(source, XBansMessages.badDuration, "input" to word)
            since = if (length.permanent) 0 else plugin.now() - length.millis
        }
        val dryRun = !confirm
        resolve(source, who, ips = false, ids = false) { target ->
            val backend = plugin.backend ?: return@resolve reply(source, XBansMessages.notReady)
            backend.prune(target.id!!, since, plugin.now(), dryRun).onSettled { counted, _ ->
                val count = (counted as? Outcome.Ok)?.value ?: return@onSettled reply(source, XBansMessages.failed)
                reply(source, if (dryRun) XBansMessages.pruneConfirm else XBansMessages.pruned, "count" to count, "player" to target.name)
                if (!dryRun) {
                    // A delete can't be undone, so the console keeps a record of each.
                    plugin.log.info("${source.name} pruned $count inactive entries from ${target.name}'s history (${target.id})" +
                        (if (since > 0) " made since ${texts.date(since)}" else "") + ".")
                    if (count > 0) cursors.clear()
                }
            }
        }
    }

    fun alts(source: CommandSource, who: String, page: Int) {
        resolve(source, who, ips = true, ids = false) { target ->
            val backend = plugin.backend ?: return@resolve reply(source, XBansMessages.notReady)
            backend.alts(target.id, target.ip).onSettled { read, _ ->
                val alts = (read as? Outcome.Ok)?.value?.filter { it.id != target.id } ?: return@onSettled reply(source, XBansMessages.failed)
                if (alts.isEmpty()) return@onSettled reply(source, XBansMessages.altsNone, "player" to target.name)
                val shown = ChatPages.of(alts, page, config[XBansConfig.lists.perPage])
                reply(source, XBansMessages.altsHeader, "player" to target.name, "count" to alts.size)
                val now = plugin.now()
                val server = if (plugin.server.proxy) null else plugin.serverName
                for (alt in shown.items) {
                    val id = alt.id!!
                    val state = when {
                        backend.active.ban(id, null, server, now) != null -> XBansMessages.altBanned
                        backend.active.mute(id, null, server, now) != null -> XBansMessages.altMuted
                        plugin.server.isOnline(id) -> XBansMessages.altOnline
                        else -> XBansMessages.altOffline
                    }
                    reply(source, XBansMessages.altsLine, "name" to alt.name, "state" to Texts.Word(state))
                }
                // What was typed, for the next page's command: an IP's name wouldn't find it again.
                footer(source, shown, "/${XBansConfig.commands.alts.command} $who {page}")
            }
        }
    }

    fun ipHistory(source: CommandSource, who: String, page: Int) {
        resolve(source, who, ips = false, ids = false) { target ->
            val backend = plugin.backend ?: return@resolve reply(source, XBansMessages.notReady)
            backend.ipHistory(target.id!!).onSettled { read, _ ->
                val uses = (read as? Outcome.Ok)?.value ?: return@onSettled reply(source, XBansMessages.failed)
                if (uses.isEmpty()) return@onSettled reply(source, XBansMessages.ipHistoryNone, "player" to target.name)
                val shown = ChatPages.of(uses, page, config[XBansConfig.lists.perPage])
                reply(source, XBansMessages.ipHistoryHeader, "player" to target.name, "count" to uses.size)
                for (use in shown.items) reply(source, XBansMessages.ipHistoryLine, "ip" to IpKeys.shown(use.ip), "first" to texts.date(use.first), "last" to texts.date(use.last))
                footer(source, shown, "/${XBansConfig.commands.iphistory.command} ${target.name} {page}")
            }
        }
    }

    // ---- alerts ----

    /** Looks for banned alts off the server's threads, after the join: never holds it up. */
    fun alertOnJoin(player: PlayerRef, name: String, ip: String) {
        val backend = plugin.backend ?: return
        // By the IP they joined on as well as by their own: a new account's first visit may not be
        // written yet, and that visit is the one this is for.
        backend.alts(null, ip).onSettled { here, _ ->
            backend.alts(player, null).onSettled { before, _ ->
                val all = LinkedHashMap<PlayerRef, Backend.IpUse>()
                for (use in (here as? Outcome.Ok)?.value.orEmpty() + (before as? Outcome.Ok)?.value.orEmpty()) use.id?.let { all.getOrPut(it) { use } }
                all.remove(player)
                val now = plugin.now()
                val server = if (plugin.server.proxy) null else plugin.serverName
                val banned = all.values.filter { backend.active.ban(it.id!!, null, server, now) != null }.map { it.name }
                if (banned.isEmpty()) return@onSettled
                plugin.platform.scheduler.run(Where.Global) { _ ->
                    texts.render(null, XBansMessages.altJoin, "player" to name, "alts" to banned.joinToString(", "))?.let(plugin.server::console)
                    for (staff in plugin.server.online()) {
                        if (plugin.server.hasPermission(staff, XBansPlugin.NOTIFY_ALTS)) texts.send(staff, XBansMessages.altJoin, "player" to name, "alts" to banned.joinToString(", "))
                    }
                }
            }
        }
    }

    /** The API's staff notification: as a punishment's announcement is sent, after [StaffNotifyEvent]. */
    fun notifyStaff(message: String, silent: Boolean) {
        val markup = co.xenastudios.core.common.text.Markup.parse(message, texts.translations.theme.tags)
        val line = texts.translations.theme.chat(markup.render())
        val server = plugin.server
        plugin.platform.scheduler.run(Where.Global) { _ ->
            server.console(line)
            val node = if (silent) Punisher.NOTIFY_SILENT else Punisher.NOTIFY_BROADCAST
            val recipients = server.online().filterTo(ArrayList()) { server.hasPermission(it, node) }
            if (recipients.isNotEmpty() && plugin.hasListeners(co.xenastudios.xbans.api.event.StaffNotifyEvent.TYPE)) {
                val event = co.xenastudios.xbans.api.event.StaffNotifyEvent(line.plain(), silent, recipients)
                plugin.bus.fire(co.xenastudios.xbans.api.event.StaffNotifyEvent.TYPE, event)
                if (event.isCancelled) recipients.clear()
            }
            for (player in recipients) server.send(player, line)
        }
    }

    // ---- /xbans import ----

    @kotlin.concurrent.Volatile
    private var importing = false

    fun importFrom(source: CommandSource, id: String, confirm: Boolean) {
        val imports = plugin.imports ?: return
        val backend = plugin.backend ?: return reply(source, XBansMessages.notReady)
        if (locked(this) { if (importing) true else false.also { importing = true } }) return reply(source, XBansMessages.importRunning)
        val importer = imports.make(id) ?: run {
            importing = false
            return reply(source, XBansMessages.importUnknown, "sources" to imports.ids.joinToString(", "))
        }
        if (confirm) reply(source, XBansMessages.importStarted, "source" to importer.name)
        val scope = plugin.punisher.scope(null)
        plugin.platform.scheduler.run(Where.Async) { _ ->
            val haul = try {
                importer.read(scope, plugin.now(), ImportContext(::running) { message, placeholders -> reply(source, message, *placeholders) })
            } catch (e: Exception) {
                importing = false
                plugin.log.error("The import from ${importer.name} failed", e)
                return@run reply(source, XBansMessages.importFailed, "reason" to (e.message ?: e.toString()))
            }
            haul.failed?.let {
                importing = false
                val message = haul.message ?: return@run reply(source, XBansMessages.importFailed, "reason" to it)
                return@run reply(source, message, *haul.placeholders.toTypedArray())
            }
            if (!confirm) {
                importing = false
                reply(source, XBansMessages.importCounted, "source" to importer.name, "bans" to haul.count(PunishmentType.BAN), "mutes" to haul.count(PunishmentType.MUTE),
                    "warnings" to haul.count(PunishmentType.WARN), "kicks" to haul.count(PunishmentType.KICK), "players" to haul.players())
                if (haul.wildcards > 0) reply(source, XBansMessages.importWildcards, "count" to haul.wildcards)
                return@run
            }
            Importing(plugin, backend, haul).write().onSettled { counts, error ->
                importing = false
                if (counts == null) {
                    plugin.log.error("The import from ${importer.name} failed: $error")
                    return@onSettled reply(source, XBansMessages.importFailed, "reason" to (error?.message ?: "the database failed"))
                }
                plugin.log.info("Imported ${counts.first} punishments from ${importer.name} (${counts.second} already there).")
                reply(source, XBansMessages.importDone, "source" to importer.name, "added" to counts.first, "skipped" to counts.second)
                if (haul.wildcards > 0) reply(source, XBansMessages.importWildcards, "count" to haul.wildcards)
            }
        }
    }

    // Whether a plugin is on here, from what the debug report lists: "LiteBans 2.17.4, Vault 1.7 (off)".
    private fun running(name: String): Boolean = plugin.about()["Plugins"].orEmpty().split(", ").any {
        it.substringBefore(' ').equals(name, true) && !it.endsWith(" (off)")
    }

    internal companion object {
        // A name as Minecraft and Bedrock (Floodgate's prefix) have them; offline-mode servers allow more.
        private val NAME = Regex("[A-Za-z0-9_.*-]{1,32}")

        fun typeWord(word: String): PunishmentType? = when (word) {
            "ban", "bans" -> PunishmentType.BAN
            "mute", "mutes" -> PunishmentType.MUTE
            "warn", "warns", "warning", "warnings" -> PunishmentType.WARN
            "kick", "kicks" -> PunishmentType.KICK
            else -> null
        }

        fun typeName(type: PunishmentType): String = when (type) {
            PunishmentType.BAN -> "bans"
            PunishmentType.MUTE -> "mutes"
            PunishmentType.WARN -> "warnings"
            PunishmentType.KICK -> "kicks"
        }
    }
}

/**
 * Pages of a long history by key range rather than by offset (decisions/2026-11-18-xbans.md).
 * Each list someone pages through remembers where each page it showed ended, so "Next page"
 * starts below the last id shown: one index range, as quick on page 300 as on page 1, and steady
 * while new punishments arrive. A page jumped to directly finds its start from the index alone.
 */
internal class Cursors {
    // For each list (viewer and filter), the id each page starts below; the oldest list goes first past KEEP.
    private val starts = LinkedHashMap<String, HashMap<Int, Long>>()

    fun page(backend: Backend?, viewer: String, filter: Backend.Filter, page: Int, perPage: Int): Pending<Outcome<ChatPages.Page<Entry>, DbError>> {
        backend ?: return pendingOf(Outcome.Err(DbError(DbError.Kind.CLOSED, "the database isn't open yet")))
        val key = "$viewer|$filter"
        val number = maxOf(1, page)
        return backend.database.read { session ->
            val total = when (val counted = backend.count(session, filter)) {
                is Outcome.Ok -> counted.value
                is Outcome.Err -> return@read counted
            }
            val shown = minOf(number, ChatPages.pages(total, perPage))
            val start = startOf(key, shown) ?: when (val bound = backend.boundary(session, filter, ChatPages.offset(shown, perPage))) {
                is Outcome.Ok -> bound.value
                is Outcome.Err -> return@read bound
            }
            val rows = if (start == Long.MIN_VALUE) emptyList() else when (val read = backend.page(session, filter, start, perPage)) {
                is Outcome.Ok -> read.value
                is Outcome.Err -> return@read read
            }
            remember(key, shown, start, rows)
            Outcome.Ok(ChatPages.of(rows, total, shown, perPage))
        }
    }

    private fun startOf(key: String, page: Int): Long? {
        if (page == 1) return Long.MAX_VALUE
        return locked(starts) { starts[key]?.get(page) }
    }

    private fun remember(key: String, page: Int, start: Long, rows: List<Entry>) {
        locked(starts) {
            val known = starts.remove(key) ?: HashMap()
            starts[key] = known
            known[page] = start
            if (rows.isNotEmpty()) known[page + 1] = rows.last().id
            while (starts.size > KEEP) starts.remove(starts.keys.first())
        }
    }

    /** Thrown out when the database changes under it, so cursors from one database never reach another. */
    fun clear() {
        locked(starts) { starts.clear() }
    }

    val remembered: Int get() = locked(starts) { starts.size }

    companion object {
        const val KEEP = 500
    }
}
