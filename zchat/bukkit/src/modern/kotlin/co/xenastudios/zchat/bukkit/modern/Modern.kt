package co.xenastudios.zchat.bukkit.modern

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

// What only newer servers have, compiled against Paper's API. Each function is called only once
// a check at startup has found what it uses (BukkitChatServer), so an old server never loads it.

/** 1.12.2 on, not Folia: the players a target selector names for [sender]. Throws IllegalArgumentException for one it can't read. */
internal fun select(sender: CommandSender, selector: String): List<Player> = Bukkit.selectEntities(sender, selector).filterIsInstance<Player>()

/**
 * 1.14 on: whether zChat 3.x kept [player]'s hidden chat and social spy in their persistent data
 * (`zchat:chat_hidden`, `zchat:social_spy`, a boolean stored as a byte).
 */
internal fun legacyChoices(plugin: Plugin, player: Player): Pair<Boolean, Boolean> {
    val data = player.persistentDataContainer
    return data.has(NamespacedKey(plugin, "chat_hidden"), PersistentDataType.BYTE) to data.has(NamespacedKey(plugin, "social_spy"), PersistentDataType.BYTE)
}
