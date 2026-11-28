package co.xenastudios.xrewind.log

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.EventListener
import co.xenastudios.core.api.event.EventPriority
import co.xenastudios.core.api.event.Subscription
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.module.Module
import co.xenastudios.core.common.module.ModuleContext
import co.xenastudios.core.common.world.BlockChange
import co.xenastudios.core.common.world.ChangeCause
import co.xenastudios.core.common.world.ContainerChange
import co.xenastudios.core.common.world.EntityDeath
import co.xenastudios.xrewind.Heard
import co.xenastudios.xrewind.ServerEvents
import co.xenastudios.xrewind.Spot
import co.xenastudios.xrewind.XRewindPlugin
import co.xenastudios.xrewind.api.Actor
import co.xenastudios.xrewind.config.XRewindConfig

/**
 * Everything `log:` asks for, heard and queued: the core's world hooks for blocks, containers and
 * kills (each kind of change listened for only while it's on, at MONITOR, so only what happened
 * is logged), and the adapter's own events for joins, signs, chat, commands and clicks. 1.x's
 * listeners, mapped onto the hooks (decisions/2026-11-27-xrewind-2.md). Made again on every
 * reload, with the config's switches as they are then.
 */
internal class Logging(private val plugin: XRewindPlugin, config: Config) : Module, ServerEvents {
    private val log = XRewindConfig.log
    internal val p: XRewindPlugin get() = plugin
    internal val breaks = config[log.blockBreak]
    internal val places = config[log.blockPlace]
    internal val explosions = config[log.explosions]
    internal val fire = config[log.fire]
    internal val liquids = config[log.liquids]
    internal val pistons = config[log.pistons]
    internal val mobs = config[log.mobs]
    internal val containers = config[log.containers]
    internal val hoppers = config[log.hoppers]
    internal val kills = config[log.kills]
    internal val decay = config[log.leafDecay]
    private val heard: Set<Heard> = buildSet {
        if (config[log.sessions]) add(Heard.SESSIONS)
        if (config[log.signs]) add(Heard.SIGNS)
        if (config[log.chat]) add(Heard.CHAT)
        if (config[log.commands]) add(Heard.COMMANDS)
        if (config[log.clicks]) add(Heard.CLICKS)
    }

    private val subscriptions = ArrayList<Subscription>()
    private var events: AutoCloseable? = null

    /** Whether anything at all is logged from the world hooks. */
    val usesHooks: Boolean get() = breaks || places || explosions || fire || liquids || pistons || mobs || containers || hoppers || kills || decay

    override fun enable(context: ModuleContext) {
        val causes = buildSet {
            if (breaks || places || liquids || fire || mobs) add(ChangeCause.PLAYER)
            if (explosions) add(ChangeCause.EXPLOSION)
            if (fire) add(ChangeCause.FIRE)
            if (pistons) add(ChangeCause.PISTON)
            if (liquids) add(ChangeCause.FLUID)
            if (mobs) add(ChangeCause.ENTITY)
            if (decay) add(ChangeCause.DECAY)
        }
        if (usesHooks) {
            val hooks = plugin.hooks()
            if (causes.isNotEmpty()) subscriptions += hooks.onBlockChange(causes, EventPriority.MONITOR, true, Blocks(this))
            if (containers || hoppers) subscriptions += hooks.onContainerChange(hoppers, Containers(this))
            if (kills) subscriptions += hooks.onEntityDeath(Kills(this))
        }
        if (heard.isNotEmpty()) events = plugin.server.listen(heard, this)
    }

    override fun disable() {
        subscriptions.forEach { it.cancel() }
        subscriptions.clear()
        events?.close()
        events = null
    }

    private class Blocks(private val l: Logging) : EventListener<BlockChange> {
        override fun on(event: BlockChange) = Changes.block(l, event)
    }

    private class Containers(private val l: Logging) : EventListener<ContainerChange> {
        override fun on(event: ContainerChange) = Changes.container(l, event)
    }

    private class Kills(private val l: Logging) : EventListener<EntityDeath> {
        override fun on(event: EntityDeath) = Changes.kill(l, event)
    }

    override fun joined(player: PlayerRef, name: String, at: Spot) = Changes.session(l = this, player, name, at, Actions.JOIN)
    override fun quit(player: PlayerRef, name: String, at: Spot) = Changes.session(l = this, player, name, at, Actions.QUIT)
    override fun sign(player: PlayerRef, name: String, at: Spot, text: String) = Changes.sign(this, player, name, at, text)
    override fun chat(player: PlayerRef, name: String, world: String, message: String) = Changes.chat(this, player, name, world, message)
    override fun command(player: PlayerRef, name: String, at: Spot, line: String) = Changes.command(this, player, name, at, line)
    override fun click(player: PlayerRef, name: String, at: Spot, state: String) = Changes.click(this, player, name, at, state)
}

/**
 * What each change is logged as: 1.x's listeners' rules, on the hooks' events. In an object of its
 * own, loaded with the first change, not as the server starts (decisions/2026-11-25-a-lighter-core.md).
 */
internal object Changes {
    private fun now(l: Logging): Int = StoredTime.of(l.p.platform.clock.now())

    private fun add(l: Logging, record: Record, world: co.xenastudios.core.api.WorldRef, x: Int, y: Int, z: Int, actor: Actor) {
        if (l.p.filters?.allow(world, x, y, z, actor.name) == false) return
        l.p.queue.add(record)
    }

    // ---- blocks ----

    fun block(l: Logging, change: BlockChange) {
        val before = change.before
        val after = change.after
        val cause = change.cause
        val action = when {
            cause == ChangeCause.FLUID -> Actions.BREAK
            after.isAir && !before.isAir -> Actions.BREAK
            else -> Actions.PLACE
        }
        // A player's change is one of several of 1.x's switches, by what it was.
        if (cause == ChangeCause.PLAYER) {
            val wanted = when {
                isLiquid(after.type) || isLiquid(before.type) && after.isAir -> l.liquids
                after.type == FIRE -> l.fire
                before.type == FARMLAND && !after.isAir -> l.mobs
                action == Actions.BREAK -> l.breaks
                else -> l.places
            }
            if (!wanted) return
        }
        if (cause == ChangeCause.ENTITY && change.source in EVERYDAY) return
        val actor = actorOf(change.player, change.playerName, cause, change.source)
        val contents = if (action == Actions.BREAK && l.p.server.holds(before.type)) l.p.server.contents(change) else null
        add(
            l,
            Record.Block(now(l), actor, change.world.name, change.x, change.y, change.z, action, before, after, contents),
            change.world, change.x, change.y, change.z, actor,
        )
    }

    // ---- containers and kills ----

    fun container(l: Logging, change: ContainerChange) {
        if (change.player == null && !l.hoppers) return
        if (change.player != null && !l.containers) return
        val actor = actorOf(change.player, change.playerName, null, change.source ?: "minecraft:hopper")
        val amount = change.amount
        add(
            l,
            Record.Container(now(l), actor, change.world.name, change.x, change.y, change.z, if (amount > 0) Actions.ADD else Actions.REMOVE,
                change.item.type, change.item, null, if (amount > 0) amount else -amount),
            change.world, change.x, change.y, change.z, actor,
        )
    }

    fun kill(l: Logging, death: EntityDeath) {
        val killer = death.killer ?: return
        val actor = player(killer, death.killerName ?: return)
        add(l, Record.Kill(now(l), actor, death.world.name, death.x, death.y, death.z, death.type), death.world, death.x, death.y, death.z, actor)
    }

    // ---- what the adapter hears ----

    fun session(l: Logging, player: PlayerRef, name: String, at: Spot, action: Int) {
        l.p.queue.add(Record.Session(now(l), player(player, name), at.world, at.x, at.y, at.z, action))
    }

    fun sign(l: Logging, player: PlayerRef, name: String, at: Spot, text: String) {
        if (text.isBlank()) return
        l.p.queue.add(Record.Text(now(l), player(player, name), at.world, at.x, at.y, at.z, Actions.SIGN, text))
    }

    // Off the server's threads, on Bukkit: only the player's world is known, not where they are.
    fun chat(l: Logging, player: PlayerRef, name: String, world: String, message: String) {
        l.p.queue.add(Record.Text(now(l), player(player, name), world, 0, 0, 0, Actions.CHAT, message))
    }

    fun command(l: Logging, player: PlayerRef, name: String, at: Spot, line: String) {
        l.p.queue.add(Record.Text(now(l), player(player, name), at.world, at.x, at.y, at.z, Actions.COMMAND, line))
    }

    fun click(l: Logging, player: PlayerRef, name: String, at: Spot, state: String) {
        val data = TextState(state)
        l.p.queue.add(Record.Block(now(l), player(player, name), at.world, at.x, at.y, at.z, Actions.CLICK, data, data))
    }

        private const val FIRE = "minecraft:fire"
        private const val FARMLAND = "minecraft:farmland"

        /** Animals going about their day (sheep eating grass, villagers farming): constant, and nobody needs it undone. */
        private val EVERYDAY = setOf(
            "minecraft:sheep", "minecraft:villager", "minecraft:bee", "minecraft:fox", "minecraft:turtle", "minecraft:snow_golem",
            "minecraft:rabbit", "minecraft:allay",
        )

        private fun isLiquid(type: String) = type == "minecraft:water" || type == "minecraft:lava" || type == "minecraft:powder_snow"

        // Players and sources each made once, not once per block an explosion destroys.
        private val players = concurrentMap<PlayerRef, Actor>()
        private val sources = concurrentMap<String, Actor>()

        fun player(player: PlayerRef, name: String): Actor {
            val known = players[player]
            if (known != null && known.name == name) return known
            if (players.size > 10_000) players.clear()
            return Actor.player(player, name).also { players[player] = it }
        }

        /**
         * Who did it, as 1.x named them: the player, or a source as `#` and its id's name (`#tnt`,
         * `#creeper`, `#water`, `#piston`, `#fire`, `#decay`, `#hopper`); a bed's explosion is `#bed`.
         */
        fun actorOf(player: PlayerRef?, name: String?, cause: ChangeCause?, source: String?): Actor {
            if (player != null && name != null) return player(player, name)
            val id = when {
                cause == ChangeCause.DECAY -> "decay"
                cause == ChangeCause.FIRE -> "fire"
                cause == ChangeCause.PISTON -> "piston"
                source == null -> cause?.name?.lowercase() ?: "unknown"
                else -> source.substringAfter(':').let { if (it.endsWith("_bed")) "bed" else it }
            }
            return sources.getOrPut(id) { Actor.source(id) }
        }
    }
