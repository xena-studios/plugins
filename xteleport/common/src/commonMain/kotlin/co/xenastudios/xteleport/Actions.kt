package co.xenastudios.xteleport

import co.xenastudios.core.api.Location
import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingFailure
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.api.reject
import co.xenastudios.core.common.command.CommandContext
import co.xenastudios.core.common.command.CommandSource
import co.xenastudios.core.common.i18n.ChatPages
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.xteleport.api.Home
import co.xenastudios.xteleport.api.Names
import co.xenastudios.xteleport.api.TeleportType
import co.xenastudios.xteleport.api.Warp
import co.xenastudios.xteleport.api.event.HomeSetEvent
import co.xenastudios.xteleport.api.event.TpaRequestEvent
import co.xenastudios.xteleport.config.RtpWorld
import co.xenastudios.xteleport.config.XTeleportConfig
import co.xenastudios.xteleport.rtp.Search
import co.xenastudios.xteleport.store.Loaded
import co.xenastudios.xteleport.teleport.NoDestination
import co.xenastudios.xteleport.teleport.Trip
import co.xenastudios.xteleport.tpa.Requests
import kotlin.math.floor

/** What each command does: [Commands] builds the tree, this runs it, made the first time a command runs. */
internal class Actions(private val plugin: XTeleportPlugin) {
    private val texts get() = plugin.texts
    private val server get() = plugin.server
    private val teleporter get() = plugin.teleporter
    private val config get() = plugin.config

    val homes = HomeActions()
    val tpa = TpaActions()
    val warps = WarpActions()

    // ---- replies ----

    fun fail(ctx: CommandContext, message: MessageKey, vararg placeholders: Pair<String, Any>): Nothing =
        ctx.fail(texts.render(ctx.source.player, message, *placeholders) ?: Component.EMPTY)

    fun reply(source: CommandSource, message: MessageKey, vararg placeholders: Pair<String, Any>) = texts.reply(source, message, *placeholders)

    /** Stops with "not available right now" until the database is open and the warps read. */
    fun requireReady(ctx: CommandContext) {
        if (!plugin.ready) fail(ctx, XTeleportMessages.unavailable)
    }

    /** Runs [work] where [source] is answered from: the player's own thread, or now for the console. */
    fun later(source: CommandSource, work: () -> Unit) {
        val player = source.player
        if (player == null) guarded(source, work) else teleporter.onPlayer(player) { guarded(source, work) }
    }

    // Work after the command returned, so the core can't catch what it throws: the same "something
    // went wrong", with the trace in the console.
    private fun guarded(source: CommandSource, work: () -> Unit) {
        try {
            work()
        } catch (e: Exception) {
            plugin.log.error("An xTeleport command failed: $e")
            reply(source, CoreMessages.commandError)
        }
    }

    /** Replies once a change is saved: [saved] if it was, otherwise "couldn't save, try again" (it was undone in memory, and the console says why). */
    fun whenSaved(source: CommandSource, write: Pending<Boolean>, saved: () -> Unit) {
        write.onSettled { ok, error ->
            later(source) {
                if (ok == true) saved() else {
                    if (error != null) plugin.log.warn(error.message ?: error.toString())
                    reply(source, XTeleportMessages.notSaved)
                }
            }
        }
    }

    /** `<world>`, `<x>`, `<y>` and `<z>`, in whole blocks. */
    fun where(at: Location): Array<Pair<String, Any>> =
        arrayOf("world" to at.world.name, "x" to floor(at.x).toLong(), "y" to floor(at.y).toLong(), "z" to floor(at.z).toLong())

    fun name(player: PlayerRef): String = server.name(player) ?: player.toString()

    /** An online player [viewer] can see, by name (any case), or the "no player online" error. */
    fun visible(ctx: CommandContext, name: String): PlayerRef =
        plugin.findVisible(name, ctx.source) ?: fail(ctx, CoreMessages.commandUnknownPlayer, "player" to name)

    fun info(ctx: CommandContext) {
        reply(
            ctx.source, XTeleportMessages.info, "version" to plugin.version, "platform" to plugin.platform.name,
            "warps" to plugin.places.warps().size, "storage" to (plugin.store?.description ?: "no database"),
        )
    }

    // ---- homes ----

    inner class HomeActions {
        private val settings get() = XTeleportConfig.homes

        /** The player's own homes, then on their thread; "not available" if they can't be read. */
        private fun withHomes(source: CommandSource, owner: PlayerRef, then: (Loaded) -> Unit) {
            plugin.homes.of(owner).onSettled { loaded, error ->
                later(source) { if (loaded == null || error != null) reply(source, XTeleportMessages.unavailable) else then(loaded) }
            }
        }

        /** A player named by staff, online or not, then their homes, then on the sender's thread. */
        private fun withOthersHomes(source: CommandSource, name: String, then: (PlayerRef, String, Loaded) -> Unit) {
            plugin.findPlayer(name).onSettled { found, _ ->
                if (found == null) {
                    later(source) { reply(source, XTeleportMessages.neverJoined, "player" to name) }
                    return@onSettled
                }
                withHomes(source, found.first) { loaded -> then(found.first, found.second, loaded) }
            }
        }

        // Another player's home, typed as player:home.
        private fun othersHome(source: CommandSource, typed: String, then: (PlayerRef, String, Home) -> Unit) {
            val owner = typed.substringBefore(':')
            val name = typed.substringAfter(':')
            withOthersHomes(source, owner) { id, ownerName, loaded ->
                val home = loaded.home(name)
                if (home == null) reply(source, XTeleportMessages.homeNotFoundOthers, "player" to ownerName, "home" to name)
                else then(id, ownerName, home)
            }
        }

        private fun others(ctx: CommandContext, node: co.xenastudios.core.common.config.ConfigKey<String>?) {
            val permission = node?.let { config[it] }.orEmpty()
            if (permission.isNotEmpty() && !ctx.source.hasPermission(permission)) fail(ctx, CoreMessages.commandNoPermission)
        }

        fun sethome(ctx: CommandContext, typed: String?) {
            val player = ctx.player
            requireReady(ctx)
            val name = typed ?: config[settings.defaultName]
            if (!Names.valid(name)) fail(ctx, XTeleportMessages.homeBadName)
            if (config[settings.bedHome] && name.equals(config[settings.bedName], ignoreCase = true)) fail(ctx, XTeleportMessages.homeReserved, "home" to name)
            withHomes(ctx.source, player) { loaded ->
                val replacing = loaded.home(name) != null
                val limit = plugin.homeLimits.of(player)
                if (!replacing && loaded.homes.size >= limit) {
                    reply(ctx.source, XTeleportMessages.homeLimit, "limit" to limit)
                    return@withHomes
                }
                val at = server.location(player) ?: return@withHomes
                if (plugin.bus.hasListeners(HomeSetEvent.TYPE) && plugin.bus.fire(HomeSetEvent.TYPE, HomeSetEvent(player, name, at, replacing)).isCancelled) return@withHomes
                whenSaved(ctx.source, plugin.homes.set(Home(player, name, at))) {
                    reply(ctx.source, if (replacing) XTeleportMessages.homeMoved else XTeleportMessages.homeSet, "home" to name)
                }
            }
        }

        fun home(ctx: CommandContext, typed: String?) {
            val player = ctx.player
            requireReady(ctx)
            if (typed != null && typed.indexOf(':') > 0) {
                others(ctx, XTeleportConfig.commands.home.permissionOthers)
                othersHome(ctx.source, typed) { _, _, home -> go(player, home) }
                return
            }
            withHomes(ctx.source, player) { loaded ->
                if (typed == null) {
                    val only = if (loaded.homes.size == 1) loaded.list.first() else loaded.home(config[settings.defaultName])
                    when {
                        only != null -> go(player, only)
                        loaded.homes.isEmpty() && plugin.extraHomes(player).isEmpty() && !(config[settings.bedHome] && server.bed(player) != null) ->
                            reply(ctx.source, XTeleportMessages.noHomes)
                        else -> list(ctx.source, player, name(player), loaded, 1, false)
                    }
                    return@withHomes
                }
                val home = loaded.home(typed)
                when {
                    home != null -> go(player, home)
                    config[settings.bedHome] && typed.equals(config[settings.bedName], ignoreCase = true) -> bed(player, config[settings.bedName])
                    else -> plugin.extraHomes(player).firstOrNull { it.name.equals(typed, ignoreCase = true) }?.let { go(player, it) }
                        ?: reply(ctx.source, XTeleportMessages.homeNotFound, "home" to typed)
                }
            }
        }

        private fun go(player: PlayerRef, home: Home) {
            if (server.world(home.location.world.name) == null) {
                texts.send(player, XTeleportMessages.worldMissing, "world" to home.location.world.name)
                return
            }
            val to = home.location
            teleporter.teleport(player, Trip.of(TeleportType.HOME, XTeleportConfig.commands.home.travel(config), true, XTeleportMessages.homeTeleported, "home" to home.name)) { pendingOf(to) }
        }

        // The bed or respawn anchor, worked out once the warmup is over: its chunk loads in the
        // background, and the block is read on its own thread to stand on top of it. A bed broken
        // since it was set isn't there to go to: "no bed", not its old spot.
        private fun bed(player: PlayerRef, bedName: String) {
            val block = server.bed(player)
            if (block == null || server.world(block.world.name) == null) {
                texts.send(player, XTeleportMessages.noBed)
                return
            }
            teleporter.teleport(player, Trip.of(TeleportType.HOME, XTeleportConfig.commands.home.travel(config), true, XTeleportMessages.homeTeleported, "home" to bedName)) {
                standOn(block)
            }
        }

        private fun standOn(block: Location): Pending<Location?> {
            val spot = newPending<Location?>()
            server.loadChunk(block.world, block.chunkX, block.chunkZ, true).onSettled { loaded, _ ->
                if (loaded != true) {
                    spot.resolve(null)
                    return@onSettled
                }
                plugin.platform.scheduler.run(Where.At(block)) {
                    val top = try {
                        server.standOn(block)
                    } catch (e: Exception) {
                        null
                    }
                    if (top == null) spot.reject(NoDestination(XTeleportMessages.noBed)) else spot.resolve(top)
                }
            }
            return spot
        }

        fun delhome(ctx: CommandContext, typed: String) {
            val player = ctx.player
            requireReady(ctx)
            if (typed.indexOf(':') > 0) {
                others(ctx, XTeleportConfig.commands.delhome.permissionOthers)
                othersHome(ctx.source, typed) { owner, ownerName, home ->
                    whenSaved(ctx.source, plugin.homes.delete(owner, home.name)) {
                        reply(ctx.source, XTeleportMessages.homeDeletedOthers, "player" to ownerName, "home" to home.name)
                    }
                }
                return
            }
            withHomes(ctx.source, player) { loaded ->
                val home = loaded.home(typed)
                if (home == null) {
                    reply(ctx.source, XTeleportMessages.homeNotFound, "home" to typed)
                    return@withHomes
                }
                whenSaved(ctx.source, plugin.homes.delete(player, typed)) { reply(ctx.source, XTeleportMessages.homeDeleted, "home" to home.name) }
            }
        }

        fun list(ctx: CommandContext, first: String?, second: String?) {
            val source = ctx.source
            requireReady(ctx)
            val self = source.player
            val others = first != null && !isNumber(first) && !(self != null && name(self).equals(first, ignoreCase = true))
            if (others) {
                others(ctx, XTeleportConfig.commands.homes.permissionOthers)
                val page = page(second)
                withOthersHomes(source, first) { id, ownerName, loaded -> list(source, id, ownerName, loaded, page, true) }
                return
            }
            val player = ctx.player
            val page = page(if (isNumber(first)) first else second)
            withHomes(source, player) { loaded -> list(source, player, name(player), loaded, page, false) }
        }

        private inner class Entry(val name: String, val at: Location)

        fun list(viewer: CommandSource, owner: PlayerRef, ownerName: String, loaded: Loaded, page: Int, others: Boolean) {
            val entries = ArrayList<Entry>()
            // A bed is only offered in one's own list: someone else's bed is theirs to find.
            if (!others && config[settings.bedHome]) {
                server.bed(owner)?.takeIf { server.world(it.world.name) != null }?.let { entries += Entry(config[settings.bedName], it) }
            }
            for (home in loaded.list) entries += Entry(home.name, home.location)
            for (home in plugin.extraHomes(owner)) if (loaded.home(home.name) == null) entries += Entry(home.name, home.location)
            if (entries.isEmpty()) {
                if (others) reply(viewer, XTeleportMessages.noHomesOthers, "player" to ownerName) else reply(viewer, XTeleportMessages.noHomes)
                return
            }
            if (others) {
                reply(viewer, XTeleportMessages.homesHeaderOthers, "player" to ownerName, "count" to loaded.homes.size)
            } else {
                val limit = plugin.homeLimits.of(owner)
                reply(viewer, XTeleportMessages.homesHeader, "count" to loaded.homes.size, "limit" to if (limit == Int.MAX_VALUE) "∞" else limit.toString())
            }
            val prefix = if (others) "/${XTeleportConfig.commands.home.command} $ownerName:" else "/${XTeleportConfig.commands.home.command} "
            val shown = ChatPages.of(entries, page, config[settings.perPage])
            for (entry in shown.items) reply(viewer, XTeleportMessages.homesEntry, "home" to entry.name, *where(entry.at), "command" to prefix + entry.name)
            footer(viewer, XTeleportMessages.homesFooter, shown, if (others) "/homes $ownerName {page}" else "/homes {page}")
        }
    }

    fun footer(viewer: CommandSource, message: MessageKey, page: ChatPages.Page<*>, command: String) {
        if (page.pages <= 1) return
        val language = texts.language(viewer.player)
        val placeholders = ChatPages.footer(page, command, language).map { it.key to (it.value as Any) }.toTypedArray()
        reply(viewer, message, *placeholders)
    }

    // ---- tpa ----

    inner class TpaActions {
        private fun expireMillis(): Long = config[XTeleportConfig.tpa.expireAfter] * 1000L

        // Checked for safety like a home: the player gone to may be over lava or the End's void.
        // Their spot is read once the warmup is over, on their own thread, and checked on the
        // thread that owns it.
        private fun trip(here: Boolean, arrivingAt: PlayerRef): Trip {
            val section = if (here) XTeleportConfig.commands.tpahere else XTeleportConfig.commands.tpa
            return Trip.of(if (here) TeleportType.TPAHERE else TeleportType.TPA, section.travel(config), true, XTeleportMessages.tpaTeleported, "player" to name(arrivingAt))
        }

        fun request(ctx: CommandContext, typed: String, here: Boolean) {
            val sender = ctx.player
            val target = visible(ctx, typed)
            if (target == sender) fail(ctx, XTeleportMessages.tpaNotYourself)
            val targetName = name(target)
            if (plugin.homes.tpaOff(target) && !server.hasPermission(sender, plugin.permissions.tptoggleBypass.node)) fail(ctx, XTeleportMessages.tpaNotTaking, "player" to targetName)
            if (plugin.requests.asked(target, sender, expireMillis())) fail(ctx, XTeleportMessages.tpaAlreadyAsked, "player" to targetName)
            // Whoever asks pays and has the cooldown, so check now that they could.
            if (teleporter.precheck(sender, trip(here, sender).paidBy(sender)) != null) return
            if (plugin.bus.hasListeners(TpaRequestEvent.TYPE) && plugin.bus.fire(TpaRequestEvent.TYPE, TpaRequestEvent(sender, target, here)).isCancelled) return
            val senderName = name(sender)
            if (!plugin.requests.add(target, Requests.Request(sender, senderName, here, plugin.requests.now()), expireMillis())) {
                fail(ctx, XTeleportMessages.tpaAlreadyAsked, "player" to targetName)
            }
            val seconds = config[XTeleportConfig.tpa.expireAfter]
            reply(ctx.source, if (here) XTeleportMessages.tpaSentHere else XTeleportMessages.tpaSent, "player" to targetName, "seconds" to seconds)
            texts.send(
                target, if (here) XTeleportMessages.tpaReceivedHere else XTeleportMessages.tpaReceived,
                "player" to senderName, "seconds" to seconds,
                "accept" to "/${XTeleportConfig.commands.tpaccept.command} $senderName", "deny" to "/${XTeleportConfig.commands.tpdeny.command} $senderName",
            )
        }

        fun answer(ctx: CommandContext, typed: String?, accept: Boolean) {
            val player = ctx.player
            var from: PlayerRef? = null
            if (typed != null) {
                from = plugin.findOnline(typed) ?: fail(ctx, XTeleportMessages.tpaNoRequestFrom, "player" to typed)
            }
            val request = takeOnline(player, from)
                ?: if (typed != null) fail(ctx, XTeleportMessages.tpaNoRequestFrom, "player" to typed) else fail(ctx, XTeleportMessages.tpaNoRequests)
            val asker = request.from
            if (!server.isOnline(asker)) fail(ctx, XTeleportMessages.tpaNoRequestFrom, "player" to request.fromName)
            val playerName = name(player)
            if (!accept) {
                reply(ctx.source, XTeleportMessages.tpaYouDenied, "player" to request.fromName)
                texts.send(asker, XTeleportMessages.tpaDenied, "player" to playerName)
                return
            }
            // /tpa: the asker comes here. /tpahere: this player goes to the asker. The asker pays.
            val moving = if (request.here) player else asker
            val destination = if (request.here) asker else player
            val trip = trip(request.here, destination).paidBy(asker)
            teleporter.onPlayer(moving) {
                // The cooldown and the cost first, on the moving player's thread: a teleport that
                // can't happen isn't "accepted", and its request stays to accept again.
                if (teleporter.precheck(moving, trip) != null) {
                    plugin.requests.add(player, request, expireMillis())
                    texts.send(player, XTeleportMessages.tpaNotReady, "player" to request.fromName)
                    return@onPlayer
                }
                texts.send(player, XTeleportMessages.tpaYouAccepted, "player" to request.fromName)
                texts.send(asker, XTeleportMessages.tpaAccepted, "player" to playerName)
                teleporter.teleport(moving, trip) { teleporter.whereIs(destination) }
            }
        }

        // The newest request still waiting whose sender is online, or the named one's.
        private fun takeOnline(to: PlayerRef, from: PlayerRef?): Requests.Request? {
            while (true) {
                val taken = plugin.requests.take(to, from, expireMillis()) ?: return null
                if (server.isOnline(taken.from)) return taken
                // The sender left; that request is gone, so look at the next one.
            }
        }

        fun cancel(ctx: CommandContext, typed: String?) {
            val player = ctx.player
            val to = typed?.let { visible(ctx, it) }
            val targets = plugin.requests.cancel(player, to, expireMillis())
            if (targets.isEmpty()) fail(ctx, XTeleportMessages.tpaNothingToCancel)
            val playerName = name(player)
            for (target in targets) texts.send(target, XTeleportMessages.tpaCancelled, "player" to playerName)
            reply(ctx.source, XTeleportMessages.tpaYouCancelled, "count" to targets.size)
        }

        fun toggle(ctx: CommandContext) {
            val player = ctx.player
            requireReady(ctx)
            // Their settings are still being read; this is rare, just after joining.
            if (plugin.homes.cached(player) == null) fail(ctx, XTeleportMessages.unavailable)
            val off = !plugin.homes.tpaOff(player)
            whenSaved(ctx.source, plugin.homes.tpaOff(player, off)) {
                reply(ctx.source, if (off) XTeleportMessages.tpaToggledOff else XTeleportMessages.tpaToggledOn)
            }
        }
    }

    // ---- warps and spawn ----

    inner class WarpActions {
        fun setwarp(ctx: CommandContext, name: String) {
            val player = ctx.player
            requireReady(ctx)
            if (!Names.valid(name)) fail(ctx, XTeleportMessages.warpBadName)
            val replacing = plugin.places.warp(name) != null
            val at = server.location(player) ?: return
            whenSaved(ctx.source, plugin.places.setWarp(Warp(name, at))) {
                reply(ctx.source, if (replacing) XTeleportMessages.warpMoved else XTeleportMessages.warpSet, "warp" to name)
            }
        }

        fun warp(ctx: CommandContext, name: String?) {
            requireReady(ctx)
            if (name == null) {
                list(ctx.source, 1)
                return
            }
            val player = ctx.player
            val warp = plugin.places.warp(name)
            // A warp the player may not use is one they don't need to know about.
            if (warp == null || !plugin.mayUse(ctx.source, warp.name)) fail(ctx, XTeleportMessages.warpNotFound, "warp" to name)
            if (server.world(warp.location.world.name) == null) fail(ctx, XTeleportMessages.worldMissing, "world" to warp.location.world.name)
            val to = warp.location
            teleporter.teleport(player, Trip.of(TeleportType.WARP, XTeleportConfig.commands.warp.travel(config), true, XTeleportMessages.warpTeleported, "warp" to warp.name)) { pendingOf(to) }
        }

        fun delwarp(ctx: CommandContext, name: String) {
            requireReady(ctx)
            val warp = plugin.places.warp(name) ?: fail(ctx, XTeleportMessages.warpNotFound, "warp" to name)
            whenSaved(ctx.source, plugin.places.deleteWarp(name)) { reply(ctx.source, XTeleportMessages.warpDeleted, "warp" to warp.name) }
        }

        fun list(viewer: CommandSource, page: Int) {
            if (!plugin.ready) {
                reply(viewer, XTeleportMessages.unavailable)
                return
            }
            val visible = plugin.places.warps().filter { plugin.mayUse(viewer, it.name) }
            if (visible.isEmpty()) {
                reply(viewer, XTeleportMessages.noWarps)
                return
            }
            reply(viewer, XTeleportMessages.warpsHeader, "count" to visible.size)
            val shown = ChatPages.of(visible, page, config[XTeleportConfig.warps.perPage])
            for (warp in shown.items) {
                reply(viewer, XTeleportMessages.warpsEntry, "warp" to warp.name, *where(warp.location), "command" to "/${XTeleportConfig.commands.warp.command} ${warp.name}")
            }
            footer(viewer, XTeleportMessages.warpsFooter, shown, "/${XTeleportConfig.commands.warps.command} {page}")
        }

        fun setspawn(ctx: CommandContext) {
            val player = ctx.player
            requireReady(ctx)
            val at = server.location(player) ?: return
            whenSaved(ctx.source, plugin.places.setSpawn(at)) { reply(ctx.source, XTeleportMessages.spawnSet) }
        }

        fun spawn(ctx: CommandContext) {
            val player = ctx.player
            requireReady(ctx)
            val to = plugin.spawnPoint() ?: fail(ctx, XTeleportMessages.failed)
            teleporter.teleport(player, Trip.of(TeleportType.SPAWN, XTeleportConfig.commands.spawn.travel(config), true, XTeleportMessages.spawnTeleported)) { pendingOf(to) }
        }
    }

    // ---- rtp ----

    /**
     * `/rtp [world]`: the cooldown and cost are checked before searching, so nobody waits for a spot
     * they can't use; then the search, then the usual warmup and teleport. Two cooldowns: a short
     * one each search starts, found or not, so nobody keeps the server generating chunks, and the
     * command's own, from arrival.
     */
    fun rtp(ctx: CommandContext, typed: String?) {
        val player = ctx.player
        val name = typed ?: config[XTeleportConfig.rtp.defaultWorld].ifBlank { null } ?: server.location(player)?.world?.name ?: ""
        val world = server.world(name)
        val rules = world?.let { plugin.rtp.allowed(ctx.source, it) } ?: fail(ctx, XTeleportMessages.rtpWorldNotAllowed, "world" to name)
        val travel = XTeleportConfig.commands.rtp.travel(config)
        val check = Trip.of(TeleportType.RTP, travel, false, XTeleportMessages.rtpTeleported)
        if (teleporter.precheck(player, check) != null) return
        // A search still running says so, rather than the cooldown it started.
        if (!plugin.rtp.searching(player) && teleporter.onCooldown(player, SEARCH_COOLDOWN)) return
        when (plugin.rtp.begin(player)) {
            Search.Start.ALREADY -> fail(ctx, XTeleportMessages.rtpAlreadySearching)
            Search.Start.BUSY -> fail(ctx, XTeleportMessages.rtpBusy)
            Search.Start.STARTED -> {}
        }
        teleporter.cooldowns.start(player, SEARCH_COOLDOWN, config[XTeleportConfig.rtp.searchCooldown])
        reply(ctx.source, XTeleportMessages.rtpSearching)
        val search = try {
            plugin.rtp.find(world, rules)
        } catch (e: Exception) {
            pendingFailure(e)
        }
        search.onSettled { found, error ->
            plugin.rtp.end(player)
            if (error != null) plugin.log.warn("An /rtp search failed: $error")
            teleporter.onPlayer(player) {
                if (found == null) {
                    texts.send(player, XTeleportMessages.rtpNotFound)
                    return@onPlayer
                }
                val to = found.location
                teleporter.teleport(player, check.saying(XTeleportMessages.rtpTeleported, *where(to))) { pendingOf(to) }
            }
        }
    }

    // ---- /xteleport import and rtpcheck ----

    fun importFrom(ctx: CommandContext, confirm: Boolean) {
        val source = ctx.source
        requireReady(ctx)
        val reader = plugin.essentials ?: fail(ctx, XTeleportMessages.importUnknown)
        plugin.platform.scheduler.run(Where.Async) {
            val found = try {
                reader.read { world -> server.world(world)?.ref?.name }
            } catch (e: Exception) {
                Essentials.Found(emptyList(), emptyList(), null, 0, listOf("Couldn't read EssentialsX's files: $e"))
            }
            later(source) { Imports(plugin, this).report(source, reader.folder, found, confirm) }
        }
    }

    fun rtpCheck(ctx: CommandContext, name: String, runs: Int) {
        val world = server.world(name)
        val rules = world?.let { plugin.rtp.rules(it) } ?: fail(ctx, XTeleportMessages.rtpCheckNotWorld, "world" to name)
        if (plugin.rtp.begin(null) != Search.Start.STARTED) fail(ctx, XTeleportMessages.rtpCheckBusy)
        RtpCheck(ctx.source, world, rules, runs).next()
    }

    // One search after another, so a check costs no more than one player's /rtp at a time. The
    // place among the searches is given back however the last one ends.
    private inner class RtpCheck(val source: CommandSource, val world: WorldInfo, val rules: RtpWorld, val runs: Int) {
        val start = plugin.platform.clock.now().toEpochMilliseconds()
        var done = 0
        var found = 0
        var tries = 0
        var last: Location? = null

        fun next() {
            val search = try {
                plugin.rtp.find(world, rules)
            } catch (e: Exception) {
                pendingFailure(e)
            }
            search.onSettled { spot, error ->
                done++
                if (spot != null) {
                    found++
                    tries += spot.tries
                    last = spot.location
                }
                if (error == null && done < runs && plugin.running) {
                    next()
                    return@onSettled
                }
                plugin.rtp.end(null)
                val ms = (plugin.platform.clock.now().toEpochMilliseconds() - start) / done
                later(source) {
                    val at = last
                    when {
                        error != null -> {
                            plugin.log.error("The RTP search failed: $error")
                            reply(source, CoreMessages.commandError)
                        }
                        at == null -> reply(source, XTeleportMessages.rtpCheckNone, "world" to world.ref.name, "runs" to done, "tries" to config[XTeleportConfig.rtp.attempts])
                        else -> reply(
                            source, XTeleportMessages.rtpCheckFound, *where(at), "found" to found, "runs" to done,
                            "tries" to (floor(tries * 10.0 / found) / 10).toString(), "ms" to ms,
                        )
                    }
                }
            }
        }
    }

    companion object {
        /** The cooldown each `/rtp` search starts. */
        const val SEARCH_COOLDOWN = "rtp-search"

        /** A page number typed, or 1. */
        fun page(typed: String?): Int = typed?.toIntOrNull()?.coerceAtLeast(1) ?: 1

        fun isNumber(typed: String?): Boolean = typed != null && typed.isNotEmpty() && typed.length < 7 && typed.all { it in '0'..'9' }
    }
}
