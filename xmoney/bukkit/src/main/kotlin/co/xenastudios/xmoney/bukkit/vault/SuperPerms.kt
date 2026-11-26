package co.xenastudios.xmoney.bukkit.vault

import net.milkbowl.vault.permission.Permission
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin

/**
 * What real Vault did for servers without a permissions plugin: a Vault `Permission` provider
 * that answers from online players' Bukkit permissions, with no groups. Registered at the lowest
 * priority, so any permissions plugin that hooks Vault (LuckPerms) wins.
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
internal class SuperPerms(owner: Plugin) : Permission() {
    init {
        plugin = owner
    }

    override fun getName(): String = "SuperPerms"

    override fun isEnabled(): Boolean = plugin.isEnabled

    override fun hasSuperPermsCompat(): Boolean = true

    override fun playerHas(world: String?, player: String?, permission: String?): Boolean {
        val online = player?.let(Bukkit::getPlayerExact) ?: return false
        return permission != null && online.hasPermission(permission)
    }

    override fun playerAdd(world: String?, player: String?, permission: String?): Boolean = false

    override fun playerRemove(world: String?, player: String?, permission: String?): Boolean = false

    override fun groupHas(world: String?, group: String?, permission: String?): Boolean = false

    override fun groupAdd(world: String?, group: String?, permission: String?): Boolean = false

    override fun groupRemove(world: String?, group: String?, permission: String?): Boolean = false

    override fun playerInGroup(world: String?, player: String?, group: String?): Boolean = false

    override fun playerAddGroup(world: String?, player: String?, group: String?): Boolean = false

    override fun playerRemoveGroup(world: String?, player: String?, group: String?): Boolean = false

    override fun getPlayerGroups(world: String?, player: String?): Array<String> = emptyArray()

    override fun getPrimaryGroup(world: String?, player: String?): String? = null

    override fun getGroups(): Array<String> = emptyArray()

    override fun hasGroupSupport(): Boolean = false
}
