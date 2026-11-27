package co.xenastudios.xvote

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.common.concurrentMap
import co.xenastudios.core.common.text.Placeholder
import co.xenastudios.xvote.api.RewardType
import co.xenastudios.xvote.api.event.VoteRewardEvent
import co.xenastudios.xvote.api.event.VoteRewardedEvent
import co.xenastudios.xvote.vote.Facts
import kotlin.random.Random

/**
 * Gives rewards, on the player's own thread (the main thread on Paper, their region's on Folia),
 * so items, messages, sounds and money reach them there; console commands go to the global thread,
 * where commands run. Items that don't fit are held for `/vote claim`, never dropped (except on
 * Fabric, whose `/give` drops them). Made with the first vote paid, not as the server starts.
 */
internal class Payout(private val plugin: XVotePlugin) {
    private val warned = concurrentMap<String, Boolean>()

    /** A reward type another plugin added, and who added it. */
    class Registered(val owner: String, val type: RewardType)

    /** Warnings about missing economies and reward types are given again after a reload. */
    fun reloaded() {
        warned.clear()
    }

    /** What a reward's text can mention: the site as players see it, as commands get it, and the player's total and streak. */
    class Context(val site: String, val commandSite: String, val total: Int, val streak: Int)

    /** Pays a counted vote's rewards. On the player's thread. */
    fun vote(player: PlayerRef, facts: Facts) {
        val current = plugin.rewards
        var names = current.earned(facts)
        if (plugin.hasListeners(VoteRewardEvent.TYPE)) {
            val event = VoteRewardEvent(player, facts.site, names)
            plugin.bus.fire(VoteRewardEvent.TYPE, event)
            if (event.isCancelled) return
            names = event.rewards
        }
        give(player, current, names, Context(current.siteName(facts.site), current.commandSite(facts.site), facts.number, facts.streak))
        if (plugin.hasListeners(VoteRewardedEvent.TYPE)) plugin.bus.fire(VoteRewardedEvent.TYPE, VoteRewardedEvent(player, facts.site, names.toList()))
    }

    /** Gives named rewards, as for the vote party. On the player's thread. */
    fun give(player: PlayerRef, current: Rewards, names: List<String>, context: Context) {
        val leftover = ArrayList<ByteArray>()
        var count = 0
        for (name in names) {
            val reward = current.reward(name) ?: continue
            try {
                count += give(player, reward, context, leftover)
            } catch (e: Exception) {
                plugin.log.warn("Couldn't give ${plugin.server.name(player) ?: player} the reward $name: $e")
            }
        }
        if (leftover.isNotEmpty()) plugin.hold(player, leftover, count)
    }

    // Returns how many items didn't fit, added to [leftover].
    private fun give(player: PlayerRef, reward: Rewards.RewardEntry, context: Context, leftover: MutableList<ByteArray>): Int {
        if (reward.chance < 100 && Random.nextDouble(100.0) >= reward.chance) return 0
        val server = plugin.server
        val name = server.name(player) ?: return 0
        if (reward.money > 0) {
            val economy = plugin.economy
            if (economy == null || !economy.ready) {
                once("money", "Reward ${reward.name} gives money, but there's no economy: install xMoney (or, on Bukkit, Vault and an economy plugin). Money rewards are skipped until then.")
            } else {
                economy.give(player, reward.money).onSettled { refused, error ->
                    if (refused != null || error != null) once("money-refused", "The economy (${economy.name}) refused a money reward for $name: ${refused ?: error}")
                }
            }
        }
        var held = 0
        if (reward.items.isNotEmpty()) {
            val (left, count) = server.give(player, reward.items)
            leftover += left
            held = count
        }
        if (reward.commands.isNotEmpty()) {
            for (command in reward.commands) {
                val line = fill(command, name, player, context).removePrefix("/")
                try {
                    server.dispatch(line)
                } catch (e: Exception) {
                    plugin.log.warn("Reward ${reward.name}'s command failed: $line ($e)")
                }
            }
        }
        val placeholders = placeholders(name, player, context)
        plugin.texts.owners(reward.message, placeholders)?.let { server.send(player, it) }
        if (reward.broadcast.isNotBlank()) {
            val line = plugin.texts.owners(reward.broadcast, placeholders)
            if (line != null) {
                for (each in server.online()) server.send(each, line)
                server.console(line)
            }
        }
        if (!reward.sound.isNone) server.playSound(player, reward.sound)
        for (custom in reward.custom) {
            val filled = fill(custom, name, player, context).trim()
            val id = filled.substringBefore(' ')
            val registered = plugin.rewardTypes[id.lowercase()]
            if (registered == null) {
                once("type:$id", "Reward ${reward.name} uses the reward type \"$id\", which no enabled plugin has added; it's skipped.")
                continue
            }
            try {
                registered.type.give(player, if (' ' in filled) filled.substringAfter(' ').trim() else "")
            } catch (e: Exception) {
                plugin.log.warn("${registered.owner}'s reward type $id failed for $name: $e")
            }
        }
        return held
    }

    private fun once(key: String, message: String) {
        if (warned.put(key, true) == null) plugin.log.warn(message)
    }

    companion object {
        fun placeholders(name: String, player: PlayerRef, context: Context): Map<String, Placeholder> = mapOf(
            "player" to Placeholder.text(name),
            "uuid" to Placeholder.text(player.toString()),
            "site" to Placeholder.text(context.site),
            "total" to Placeholder.text(context.total),
            "streak" to Placeholder.text(context.streak),
        )

        /** A command or custom reward with its placeholders filled in, as plain text. */
        fun fill(text: String, name: String, player: PlayerRef, context: Context): String =
            text.replace("<player>", name).replace("%player%", name).replace("<uuid>", player.toString())
                .replace("<site>", context.commandSite).replace("<total>", context.total.toString()).replace("<streak>", context.streak.toString())
    }
}
