package co.xenastudios.xvote.bukkit

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.bukkit.online
import co.xenastudios.core.bukkit.ref
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Sound
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.text.Component
import co.xenastudios.xvote.Economy
import co.xenastudios.xvote.VoteServer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream

/**
 * xVote's [VoteServer] on Bukkit, 1.8.8 to the newest: players, console commands on the global
 * thread, sounds by name, and reward items. Items are read as `/give` writes them: through the item
 * factory's own `/give` reader where the server has it (1.20.5 on, with components), or as a material
 * and an optional `:data` below. What doesn't fit is kept as Paper's own bytes where Paper can write
 * them (1.x's format, so the 1.x jar reads them back), or Bukkit's serialised item below that.
 */
internal class BukkitVoteServer(private val platform: BukkitPlatform, private val cache: PlayerCache) : VoteServer {
    private val server = platform.plugin.server
    private val log = platform.log

    override fun online(): List<PlayerRef> = server.onlinePlayers.map { it.ref }

    override fun name(player: PlayerRef): String? = player.online(server)?.name

    override fun send(player: PlayerRef, message: Component) {
        player.online(server)?.let { platform.text.send(it, message) }
    }

    override fun console(message: Component) {
        platform.text.send(server.consoleSender, message)
    }

    override fun hasPermission(player: PlayerRef, node: String): Boolean = player.online(server)?.hasPermission(node) ?: false

    override fun dispatch(line: String) {
        platform.scheduler.run(Where.Global) { _ ->
            try {
                server.dispatchCommand(server.consoleSender, line)
            } catch (e: Exception) {
                log.warn("A reward's command failed: $line ($e)")
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun nameOf(player: PlayerRef): String? = server.getOfflinePlayer(java.util.UUID.fromString(player.toString())).name

    // The family's player cache knows everyone who joined since it was there; a server's own user
    // cache is read only by a blocking lookup, which a vote's thread mustn't wait on.
    override fun known(name: String): Pair<PlayerRef, String>? = cache.find(name)?.let { it.player to it.name }

    // ---- items ----

    // The item factory's /give reader (Bukkit 1.20.5+), found with the first item read.
    @Volatile
    private var reader: Method? = null

    @Volatile
    private var looked = false

    override fun item(written: String): Outcome<Any, String> {
        var text = written.trim()
        var amount = 1
        val space = text.lastIndexOf(' ')
        if (space > 0 && text.substring(space + 1).let { it.length in 1..4 && it.all(Char::isDigit) }) {
            amount = text.substring(space + 1).toInt()
            text = text.substring(0, space).trim()
        }
        if (text.isEmpty() || amount < 1) return Outcome.Err("isn't an item, such as diamond 3")
        val factory = factoryReader()
        val item = if (factory != null) {
            try {
                factory.invoke(server.itemFactory, text) as ItemStack
            } catch (e: java.lang.reflect.InvocationTargetException) {
                // The server says why in its own words, which owners know from /give.
                return Outcome.Err("isn't an item, such as diamond 3 (${e.targetException.message})")
            }
        } else {
            legacy(text) ?: return Outcome.Err("isn't an item, such as diamond 3")
        }
        if (item.type == Material.AIR) return Outcome.Err("is air")
        item.amount = minOf(amount, 6400)
        return Outcome.Ok(item)
    }

    private fun factoryReader(): Method? {
        if (!looked) {
            reader = try {
                server.itemFactory.javaClass.getMethod("createItemStack", String::class.java)
            } catch (e: NoSuchMethodException) {
                null
            }
            looked = true
        }
        return reader
    }

    // diamond, minecraft:diamond, wool:14: a material, and on old servers a data value.
    private fun legacy(text: String): ItemStack? {
        val bare = text.removePrefix("minecraft:")
        val name = bare.substringBefore(':')
        val data = bare.substringAfter(':', "").toShortOrNull()
        val material = Material.matchMaterial(name) ?: Material.matchMaterial(name.uppercase()) ?: return null
        @Suppress("DEPRECATION")
        return if (data != null) ItemStack(material, 1, data) else ItemStack(material, 1)
    }

    override fun give(player: PlayerRef, items: List<Any>): Pair<List<ByteArray>, Int> {
        val online = player.online(server) ?: return emptyList<ByteArray>() to 0
        val leftover = ArrayList<ItemStack>()
        for (item in items) {
            for (stack in split(item as ItemStack)) leftover += online.inventory.addItem(stack).values
        }
        return leftover.map(::bytes) to leftover.sumOf { it.amount }
    }

    override fun handOut(player: PlayerRef, held: List<ByteArray>): Triple<Int, List<ByteArray>, Int> {
        val online = player.online(server) ?: return Triple(0, held, 0)
        var given = 0
        val leftover = ArrayList<ItemStack>()
        for (bytes in held) {
            val item = try {
                read(bytes)
            } catch (e: Exception) {
                log.warn("A held reward item of ${online.name}'s couldn't be read, so it's dropped: $e")
                continue
            }
            val left = online.inventory.addItem(item).values
            leftover += left
            given += item.amount - left.sumOf { it.amount }
        }
        return Triple(given, leftover.map(::bytes), leftover.sumOf { it.amount })
    }

    // An item written with more than a stack goes in as full stacks.
    private fun split(item: ItemStack): List<ItemStack> {
        val max = maxOf(1, item.maxStackSize)
        val stacks = ArrayList<ItemStack>()
        var left = item.amount
        while (left > 0) {
            stacks += item.clone().also { it.amount = minOf(max, left) }
            left -= max
        }
        return stacks
    }

    // Paper's own bytes (1.x's), or Bukkit's serialised item.
    private val paperWrite: Method? = try {
        ItemStack::class.java.getMethod("serializeAsBytes")
    } catch (e: NoSuchMethodException) {
        null
    }

    private fun bytes(item: ItemStack): ByteArray {
        paperWrite?.let { return it.invoke(item) as ByteArray }
        val out = ByteArrayOutputStream()
        BukkitObjectOutputStream(out).use { it.writeObject(item) }
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray): ItemStack {
        // Java serialisation starts with 0xACED; anything else is Paper's.
        if (bytes.size > 2 && bytes[0] == 0xAC.toByte() && bytes[1] == 0xED.toByte()) {
            return BukkitObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() as ItemStack }
        }
        val method = ItemStack::class.java.getMethod("deserializeBytes", ByteArray::class.java)
        return method.invoke(null, bytes) as ItemStack
    }

    override fun playSound(player: PlayerRef, sound: Sound) {
        if (sound.isNone) return
        val online = player.online(server) ?: return
        online.playSound(online.location, sound.name, sound.volume, sound.pitch)
    }

    // Bukkit's sound names changed in 1.9 and its registry in 1.21: there's no one way to ask.
    override fun knowsSound(sound: Sound): Boolean? = null
}

/**
 * Money rewards through Vault's economy, whichever plugin provides it (EssentialsX, CMI, xMoney
 * itself), called by name through Vault's own classes: 1.x's way, kept for servers without xMoney.
 */
internal class VaultEconomy private constructor(private val provider: Any, private val deposit: Method, private val provided: String) : Economy {
    override val name: String get() = "Vault ($provided)"
    override val ready: Boolean get() = true

    override fun give(player: PlayerRef, amount: Double): Pending<String?> = try {
        val response = deposit.invoke(provider, org.bukkit.Bukkit.getOfflinePlayer(java.util.UUID.fromString(player.toString())), amount)
        val ok = response.javaClass.getMethod("transactionSuccess").invoke(response) == true
        pendingOf(if (ok) null else response.javaClass.getField("errorMessage").get(response)?.toString() ?: "refused")
    } catch (e: ReflectiveOperationException) {
        pendingOf(e.toString())
    }

    companion object {
        fun find(platform: BukkitPlatform): VaultEconomy? {
            val services = platform.plugin.server.servicesManager
            val type = services.knownServices.firstOrNull { it.name == "net.milkbowl.vault.economy.Economy" } ?: return null
            val registration = services.getRegistration(type) ?: return null
            val provider = registration.provider ?: return null
            return try {
                val deposit = type.getMethod("depositPlayer", org.bukkit.OfflinePlayer::class.java, Double::class.javaPrimitiveType)
                VaultEconomy(provider, deposit, registration.plugin?.name ?: "?")
            } catch (e: NoSuchMethodException) {
                null
            }
        }
    }
}
