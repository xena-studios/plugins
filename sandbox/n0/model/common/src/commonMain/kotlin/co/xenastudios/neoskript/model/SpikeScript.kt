package co.xenastudios.neoskript.model

import co.xenastudios.core.api.Location

/**
 * What NeoSkript's compiler would make of this script, written by hand against the model, so
 * the same "compiled" code runs on every platform:
 *
 * ```
 * on join:
 *     send "Welcome, %name of player%, to %world of player%!" to player
 *     give player 16 dirt
 *     set block at location 2 north of player to glass
 * on chat:
 *     if message is "cancel me":
 *         cancel event
 *         stop
 *     send "You said: %message%" to player
 * on break:
 *     send "You broke %type of event-block% holding %type of tool of player%" to player
 * on place:
 *     send "You placed %type of event-block%, %item amount of tool of player% left" to player
 * on damage:
 *     if damage is 7:
 *         cancel event
 * on death:
 *     broadcast "%type of victim% died at %location of victim%"
 * on quit:
 *     broadcast "%name of player% left"
 * ```
 *
 * As compiled code does, everything a trigger needs from the platform is resolved once, as the
 * script loads (the bindings, their values, the types it names), and the triggers call those
 * directly. A value the platform can't give fails the load here, as a parse error would.
 *
 * [log] stands in for the console; [heard] keeps what the triggers saw, for the checks.
 */
public class SpikeScript(private val game: Game, private val log: (String) -> Unit) {

    /** What each trigger saw, newest last: `damage 7.0 cancelled`, for [Check]. */
    public val heard: MutableList<String> = ArrayList()

    private fun bind(kind: EventKind): EventBinding = game.events.binding(kind) ?: error("$kind can't be heard on ${game.platform}")

    private fun EventBinding.need(key: EventValueKey): EventValue = value(key) ?: error("$kind has no $key on ${game.platform}")

    private val join = bind(EventKind.JOIN)
    private val joinPlayer = join.need(EventValueKey.PLAYER)
    private val quit = bind(EventKind.QUIT)
    private val quitPlayer = quit.need(EventValueKey.PLAYER)
    private val chat = bind(EventKind.CHAT)
    private val chatPlayer = chat.need(EventValueKey.PLAYER)
    private val chatMessage = chat.need(EventValueKey.MESSAGE)
    private val breaking = bind(EventKind.BLOCK_BREAK)
    private val breakPlayer = breaking.need(EventValueKey.PLAYER)
    private val breakBlock = breaking.need(EventValueKey.BLOCK)
    private val placing = bind(EventKind.BLOCK_PLACE)
    private val placePlayer = placing.need(EventValueKey.PLAYER)
    private val placeBlock = placing.need(EventValueKey.BLOCK)
    private val damage = bind(EventKind.DAMAGE)
    private val damageVictim = damage.need(EventValueKey.VICTIM)
    private val damageAmount = damage.need(EventValueKey.DAMAGE)
    private val death = bind(EventKind.DEATH)
    private val deathVictim = death.need(EventValueKey.VICTIM)

    private val dirt = game.itemTypes.byId("dirt") ?: error("no dirt")
    private val glass = game.blockTypes.byId("glass") ?: error("no glass")

    private val listening = ArrayList<AutoCloseable>()

    /** Listens for the script's events, as a script load does: Skript's priority, cancelled events skipped. */
    public fun load() {
        fun on(binding: EventBinding, trigger: (Any) -> Unit) {
            listening += game.events.listen(binding.kind, Priority.HIGH, ignoreCancelled = true) { event ->
                try {
                    trigger(event)
                } catch (e: Exception) {
                    // One trigger's failure never reaches the platform's event.
                    log("N0 ERROR in ${binding.kind}: $e")
                }
            }
        }
        on(join, ::onJoin)
        on(quit, ::onQuit)
        on(chat, ::onChat)
        on(breaking, ::onBreak)
        on(placing, ::onPlace)
        on(damage, ::onDamage)
        on(death, ::onDeath)
    }

    /** Stops listening, as an unload does. */
    public fun unload() {
        listening.forEach { it.close() }
        listening.clear()
    }

    private fun onJoin(event: Any) {
        val player = joinPlayer.get(event) ?: return
        val name = game.players.name(player)
        val world = game.entities.world(player)
        game.players.send(player, "Welcome, $name, to ${game.worlds.name(world)}!")
        game.players.give(player, game.items.create(dirt, 16))
        val at = game.entities.location(player)
        val target = Location(at.world, at.x, at.y, at.z - 2)
        val block = game.blocks.at(target) ?: return
        game.blocks.setType(block, glass)
        heard += "join $name"
        log("N0 JOIN $name: ${game.blocks.type(block)} at ${place(block)}")
    }

    private fun onQuit(event: Any) {
        val player = quitPlayer.get(event) ?: return
        heard += "quit ${game.players.name(player)}"
        log("N0 QUIT ${game.players.name(player)} left")
    }

    private fun onChat(event: Any) {
        // Off the server's threads on Paper (its chat is async): only names, messages and sends here.
        val player = chatPlayer.get(event) ?: return
        val message = chatMessage.get(event) as String
        if (message == "cancel me") {
            chat.setCancelled(event, true)
            heard += "chat cancelled"
            log("N0 CHAT ${game.players.name(player)} cancelled")
            return
        }
        game.players.send(player, "You said: $message")
        heard += "chat $message"
        log("N0 CHAT ${game.players.name(player)}: $message")
    }

    private fun onBreak(event: Any) {
        val player = breakPlayer.get(event) ?: return
        val block = breakBlock.get(event) ?: return
        val tool = game.players.heldItem(player)?.let { game.items.type(it).id } ?: "nothing"
        val type = game.blocks.type(block)
        game.players.send(player, "You broke $type holding $tool")
        heard += "break $type"
        log("N0 BREAK ${game.players.name(player)} broke $type at ${place(block)} holding $tool")
    }

    private fun onPlace(event: Any) {
        val player = placePlayer.get(event) ?: return
        val block = placeBlock.get(event) ?: return
        val left = game.players.heldItem(player)?.let { game.items.amount(it) } ?: 0
        val type = game.blocks.type(block)
        game.players.send(player, "You placed $type, $left left")
        heard += "place $type"
        log("N0 PLACE ${game.players.name(player)} placed $type at ${place(block)}, $left left")
    }

    private fun onDamage(event: Any) {
        val amount = damageAmount.get(event) as Double
        val victim = damageVictim.get(event) ?: return
        if (amount == 7.0) {
            damage.setCancelled(event, true)
            heard += "damage $amount cancelled"
            return
        }
        heard += "damage $amount to ${game.entities.type(victim)}"
    }

    private fun onDeath(event: Any) {
        val victim = deathVictim.get(event) ?: return
        val at = game.entities.location(victim)
        heard += "death ${game.entities.type(victim)}"
        log("N0 DEATH ${game.entities.type(victim)} died at ${at.blockX} ${at.blockY} ${at.blockZ}")
    }

    private fun place(block: Any): String {
        val at = game.blocks.location(block)
        return "${at.world} ${at.blockX} ${at.blockY} ${at.blockZ}"
    }
}
