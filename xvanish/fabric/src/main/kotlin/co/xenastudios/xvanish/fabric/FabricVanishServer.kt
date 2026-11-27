package co.xenastudios.xvanish.fabric

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.fabric.MinecraftRenderer
import co.xenastudios.core.fabric.player
import co.xenastudios.core.fabric.ref
import co.xenastudios.xvanish.Effects
import co.xenastudios.xvanish.Switch
import co.xenastudios.xvanish.VanishServer
import co.xenastudios.xvanish.VanishView
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile
import net.minecraft.network.chat.Component as McComponent
//? if identifier
import net.minecraft.world.entity.ai.attributes.Attributes

/**
 * What xVanish does on a Fabric server, with Minecraft's own classes and Fabric API's callbacks,
 * no mixins: Fabric has no `hidePlayer`, so a hidden player's tab list entry is taken off each
 * viewer's list (`ClientboundPlayerInfoRemovePacket`) and their entity off each viewer's world
 * as Minecraft starts tracking it for them (Fabric's `EntityTrackingEvents.START_TRACKING`). The
 * join and quit messages, deaths and advancements are left out through Fabric's game-message
 * event. Everything is on the server thread, Fabric's only one.
 *
 * What a Fabric server has no event for isn't done here, and the config's comments and the docs
 * say so: the server list, mob targeting, pickup, physical blocks, sculk, raids, silent
 * containers, collisions, sleeping, spawning and hunger (decisions/2026-11-27-xvanish-1.md).
 */
internal class FabricVanishServer(private val platform: FabricPlatform) : VanishServer {

    override val features: VanishServer.Features = VanishServer.Features(
        "Minecraft's player-info and entity packets, per viewer",
        setOf(Switch.PLAYERS, Switch.DEATH_MESSAGES, Switch.ADVANCEMENT_MESSAGES, Switch.INVULNERABLE, Switch.CHAT),
    )

    // Whom each viewer mustn't see, by uuid: kept so it's sent again as either joins, and so an
    // entity Minecraft starts tracking for a viewer is taken away again.
    private val hidden = ConcurrentHashMap<UUID, MutableSet<UUID>>()

    // Players between Minecraft's making their connection and their being placed in the world:
    // xVanish decides then, so their join message can be left out, and sends them nothing yet.
    private val joining = ConcurrentHashMap<UUID, ServerPlayer>()
    private val joiningVanished = ConcurrentHashMap.newKeySet<String>()
    private val quitVanished = ConcurrentHashMap.newKeySet<String>()
    private val waiting = ConcurrentHashMap<UUID, MutableList<() -> Unit>>()

    // The switches' listeners: Fabric's callbacks can't be unregistered, so each is registered
    // once and does nothing while its switch is off.
    @Volatile private var players: VanishView? = null
    @Volatile private var deaths: VanishView? = null
    @Volatile private var advancements: VanishView? = null
    @Volatile private var harmless: VanishView? = null
    @Volatile private var chat: VanishView? = null

    init {
        ServerPlayConnectionEvents.INIT.register { handler, _ ->
            val view = players ?: return@register
            val player = handler.player
            joining[player.uuid] = player
            if (view.joining(player.ref, player.scoreboardName)) joiningVanished += player.scoreboardName
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val player = handler.player
            joining.remove(player.uuid)
            // Placed: what was decided as they connected is sent now, both ways.
            hideAround(player)
            // Fabric's JOIN comes as Minecraft makes the player's connection, before it sends them
            // everyone's tab list entries and everyone theirs: the entries are taken off again on
            // the next tick, or a viewer joining would list a vanished player (the real-client
            // boot on Fabric found it).
            platform.scheduler.run(Where.Global) { platform.server?.playerList?.getPlayer(player.uuid)?.let(::hideAround) }
            waiting.remove(player.uuid)?.forEach { it() }
        }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ ->
            val player = handler.player
            joining.remove(player.uuid)
            waiting.remove(player.uuid)
            val view = players
            if (view != null && view.quitting(player.ref)) quitVanished += player.scoreboardName
            hidden.remove(player.uuid)
        }
        EntityTrackingEvents.START_TRACKING.register { entity, viewer ->
            if (entity is ServerPlayer && hidden[viewer.uuid]?.contains(entity.uuid) == true) {
                viewer.connection.send(ClientboundRemoveEntitiesPacket(entity.id))
            }
        }
        ServerMessageEvents.ALLOW_GAME_MESSAGE.register { _, message, _ -> allow(message) }
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, _, _ ->
            val view = harmless
            !(view != null && entity is ServerPlayer && !view.nobody && view.isVanished(entity.ref))
        }
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { message, sender, _ ->
            val view = chat
            view == null || view.nobody || view.mayChat(sender.ref, message.signedContent())
        }
    }

    // A join, quit, death or advancement message about a vanished player isn't sent.
    private fun allow(message: McComponent): Boolean {
        val contents = message.contents as? TranslatableContents ?: return true
        val key = contents.key
        val about = contents.args.firstOrNull()?.let { (it as? McComponent)?.string ?: it.toString() } ?: return true
        return when {
            key.startsWith("multiplayer.player.joined") -> !joiningVanished.remove(about)
            key == "multiplayer.player.left" -> !quitVanished.remove(about)
            key.startsWith("death.") -> deaths?.let { !vanished(it, about) } ?: true
            key.startsWith("chat.type.advancement.") -> advancements?.let { !vanished(it, about) } ?: true
            else -> true
        }
    }

    private fun vanished(view: VanishView, name: String): Boolean {
        if (view.nobody) return false
        val player = platform.server?.playerList?.getPlayerByName(name) ?: return false
        return view.isVanished(player.ref)
    }

    private fun player(ref: PlayerRef): ServerPlayer? = platform.server?.player(ref) ?: joining[UUID.fromString(ref.toString())]

    override fun online(): Collection<PlayerRef> = platform.server?.playerList?.players?.map { it.ref }.orEmpty()

    override fun name(player: PlayerRef): String? = player(player)?.scoreboardName

    override fun hasPermission(player: PlayerRef, node: String): Boolean = player(player)?.let { platform.permissions.has(it, node) } == true

    // On the server thread, once the player is in the world.
    private fun onPlayer(ref: PlayerRef, work: (ServerPlayer) -> Unit) {
        val server = platform.server ?: return
        val run = {
            val uuid = UUID.fromString(ref.toString())
            val placed = server.player(ref)
            if (placed != null) work(placed) else if (joining.containsKey(uuid)) waiting.computeIfAbsent(uuid) { ArrayList() } += { server.player(ref)?.let(work) }
        }
        if (server.isSameThread) run() else server.execute(run)
    }

    override fun send(player: PlayerRef, message: Component) {
        val text = MinecraftRenderer.draw(message)
        onPlayer(player) { it.sendSystemMessage(text) }
    }

    override fun console(message: Component) {
        platform.log.info(message.plain())
    }

    override fun hide(viewer: PlayerRef, target: PlayerRef) {
        val v = UUID.fromString(viewer.toString())
        val t = UUID.fromString(target.toString())
        if (!hidden.computeIfAbsent(v) { ConcurrentHashMap.newKeySet() }.add(t)) return
        val server = platform.server ?: return
        val placed = server.playerList.getPlayer(v) ?: return
        if (server.playerList.getPlayer(t) != null) send(placed, t, hide = true)
    }

    override fun show(viewer: PlayerRef, target: PlayerRef) {
        val v = UUID.fromString(viewer.toString())
        val t = UUID.fromString(target.toString())
        if (hidden[v]?.remove(t) != true) return
        val server = platform.server ?: return
        val placed = server.playerList.getPlayer(v) ?: return
        val shown = server.playerList.getPlayer(t) ?: return
        placed.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(listOf(shown)))
        retrack(shown)
    }

    // What [player] mustn't see taken off their view, and [player] off the view of whoever mustn't see them.
    private fun hideAround(player: ServerPlayer) {
        for ((viewer, targets) in hidden) {
            if (viewer == player.uuid) targets.forEach { send(player, it, hide = true) }
            else if (player.uuid in targets) platform.server?.playerList?.getPlayer(viewer)?.let { send(it, player.uuid, hide = true) }
        }
    }

    private fun send(viewer: ServerPlayer, target: UUID, hide: Boolean) {
        if (!hide) return
        viewer.connection.send(ClientboundPlayerInfoRemovePacket(listOf(target)))
        platform.server?.playerList?.getPlayer(target)?.let { viewer.connection.send(ClientboundRemoveEntitiesPacket(it.id)) }
    }

    // Who's to be tracked again, once this tick: Minecraft pairs an entity with a viewer only as
    // it comes into range, so a player shown again is taken out of the world's tracking and put
    // back, which pairs them with everyone in range now (and resends their own chunks once).
    private val retracking = ConcurrentHashMap.newKeySet<UUID>()

    private fun retrack(player: ServerPlayer) {
        if (!retracking.add(player.uuid)) return
        val server = platform.server ?: return
        server.execute {
            retracking.remove(player.uuid)
            val now = server.playerList.getPlayer(player.uuid) ?: return@execute
            val level: Any = now.level()
            val chunks = (level as ServerLevel).chunkSource
            chunks.removeEntity(now)
            chunks.addEntity(now)
        }
    }

    override fun hiddenByDefault(target: PlayerRef, hidden: Boolean): Boolean = false

    // What effects changed, per player, to undo exactly that.
    private class Applied(val flew: Boolean, val nightVision: Boolean, val bar: ServerBossEvent?, val locator: Double?)

    private val applied = ConcurrentHashMap<UUID, Applied>()

    override fun effects(player: PlayerRef, effects: Effects?) {
        onPlayer(player) { p ->
            applied.remove(p.uuid)?.let { undo(p, it) }
            if (effects == null) return@onPlayer
            val abilities = p.abilities
            val couldFly = abilities.mayfly
            if (effects.fly && !couldFly) {
                abilities.mayfly = true
                p.onUpdateAbilities()
            }
            val vision = effects.nightVision && !p.hasEffect(MobEffects.NIGHT_VISION)
            if (vision) p.addEffect(MobEffectInstance(MobEffects.NIGHT_VISION, MobEffectInstance.INFINITE_DURATION, 0, false, false))
            val bar = effects.bossBar?.let { title ->
                //? if overlay_message {
                // 26.1 gave a boss bar its uuid (the constant marks the 26.1 line).
                ServerBossEvent(UUID.randomUUID(), MinecraftRenderer.draw(title), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS)
                //?} else
                //ServerBossEvent(MinecraftRenderer.draw(title), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS)
            }?.also { it.addPlayer(p) }
            var locator: Double? = null
            //? if identifier {
            // 1.21.6 on: the locator bar; the constant marks the 1.21.11 line.
            if (effects.offLocatorBar) {
                p.getAttribute(Attributes.WAYPOINT_TRANSMIT_RANGE)?.let {
                    locator = it.baseValue
                    it.baseValue = 0.0
                }
            }
            //?}
            applied[p.uuid] = Applied(couldFly, vision, bar, locator)
        }
    }

    private fun undo(p: ServerPlayer, was: Applied) {
        val abilities = p.abilities
        if (!was.flew && !p.isCreative && !p.isSpectator && abilities.mayfly) {
            abilities.mayfly = false
            abilities.flying = false
            p.onUpdateAbilities()
        }
        if (was.nightVision) p.removeEffect(MobEffects.NIGHT_VISION)
        was.bar?.removeAllPlayers()
        //? if identifier
        was.locator?.let { range -> p.getAttribute(Attributes.WAYPOINT_TRANSMIT_RANGE)?.baseValue = range }
    }

    override fun listen(switch: Int, view: VanishView): AutoCloseable {
        when (switch) {
            Switch.PLAYERS -> players = view
            Switch.DEATH_MESSAGES -> deaths = view
            Switch.ADVANCEMENT_MESSAGES -> advancements = view
            Switch.INVULNERABLE -> harmless = view
            Switch.CHAT -> chat = view
        }
        return Off(switch)
    }

    private inner class Off(private val switch: Int) : AutoCloseable {
        override fun close() {
            when (switch) {
                Switch.PLAYERS -> players = null
                Switch.DEATH_MESSAGES -> deaths = null
                Switch.ADVANCEMENT_MESSAGES -> advancements = null
                Switch.INVULNERABLE -> harmless = null
                Switch.CHAT -> chat = null
            }
        }
    }
}
