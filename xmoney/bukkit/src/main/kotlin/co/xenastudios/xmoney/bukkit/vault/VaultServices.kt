package co.xenastudios.xmoney.bukkit.vault

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.resolve
import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.xmoney.BalanceSource
import co.xenastudios.xmoney.XMoneyPlugin
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.zip.ZipFile
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority

/**
 * xMoney's stand-in for Vault. The jar carries Vault's and VaultUnlocked's API classes
 * (unmodified, LGPL-3.0) and says `provides: [Vault]` (Paper, and Spigot from 1.15.2), so plugins
 * that depend on Vault load and find those classes here. This registers the economy under both
 * APIs at the highest priority, so plugins pick it over any other economy still installed (one an
 * import reads from), and, when no real Vault is installed, a Permission provider for servers
 * without a permissions plugin, as real Vault did.
 *
 * Below 1.15.2 Bukkit has no `provides`, so a real Vault.jar goes beside xMoney there. Its own
 * copy of Vault's `Economy` may then be the one other plugins see (whichever class loader the
 * server asks first), so xMoney registers under that class too, through a proxy of
 * [LegacyEconomy]: plugins find the economy whichever copy they were linked against.
 */
@Suppress("DEPRECATION")
internal class VaultServices(private val plugin: Plugin, private val platform: BukkitPlatform, bridge: VaultHolder) {
    private val legacy = LegacyEconomy(bridge)
    private val unlocked = UnlockedEconomy(bridge)
    private var permissions: SuperPerms? = null
    private var foreign: Pair<Class<Any>, Any>? = null

    /** The real Vault plugin installed beside xMoney, if there is one (below 1.15.2, or by mistake). */
    private fun realVault(): Plugin? = Bukkit.getPluginManager().plugins.firstOrNull { it.name == "Vault" && it !== plugin }

    fun register() {
        val services = Bukkit.getServicesManager()
        services.register(net.milkbowl.vault.economy.Economy::class.java, legacy, plugin, ServicePriority.Highest)
        services.register(net.milkbowl.vault2.economy.Economy::class.java, unlocked, plugin, ServicePriority.Highest)
        val vault = realVault()
        if (vault == null) {
            // Real Vault's fallback for servers without a permissions plugin; any that hooks Vault wins.
            permissions = SuperPerms(plugin).also {
                services.register(net.milkbowl.vault.permission.Permission::class.java, it, plugin, ServicePriority.Lowest)
            }
        } else {
            foreign = ForeignVault.register(vault, legacy, plugin, platform)
        }
    }

    fun unregister() {
        val services = Bukkit.getServicesManager()
        services.unregister(net.milkbowl.vault.economy.Economy::class.java, legacy)
        services.unregister(net.milkbowl.vault2.economy.Economy::class.java, unlocked)
        permissions?.let { services.unregister(net.milkbowl.vault.permission.Permission::class.java, it) }
        foreign?.let { (type, proxy) -> services.unregister(type, proxy) }
        permissions = null
        foreign = null
    }

    /**
     * Says what to do about a Vault or VaultUnlocked jar beside xMoney: below 1.15.2 Vault.jar is
     * needed (Bukkit can't let xMoney stand in for it); from 1.15.2 xMoney provides Vault, and on
     * Paper the real jar was refused as a duplicate (the error above). Reads files: off the server's threads.
     */
    fun checkJars() {
        val provides = platform.server.atLeast(1, 15, 2)
        val folder = plugin.dataFolder.toPath().parent
        if (!provides) {
            if (realVault() == null) {
                platform.log.warn("This server is older than 1.15.2, so xMoney can't stand in for Vault itself: put Vault.jar " +
                    "(https://www.spigotmc.org/resources/vault.34315/) in plugins/ so plugins that need Vault find xMoney's economy.")
            }
            return
        }
        // Only a jar named for Vault is opened, so a server without one never loads the reading code.
        val names = try {
            folder?.toFile()?.list()?.filter { it.endsWith(".jar") && it.contains("vault", ignoreCase = true) }.orEmpty()
        } catch (e: SecurityException) {
            emptyList()
        }
        if (names.isEmpty()) return
        for (jar in VaultJars.named(folder!!, names)) {
            platform.log.warn("$jar is in plugins/. xMoney provides the Vault and VaultUnlocked APIs itself, so it isn't needed" +
                (if (realVault() == null) ", and the server didn't load it: that's the \"Could not load plugin '$jar'\" error above." else ".") +
                " Other plugins use xMoney instead. Delete $jar.")
        }
    }

    /** Whether [economy] is xMoney's own, so an import from Vault skips it. */
    fun ours(economy: Any): Boolean = economy === legacy || economy === unlocked || economy === foreign?.second

    /** Another economy plugin registered with Vault, for `/xmoney import vault`. */
    fun importer(): BalanceSource = VaultImport(this, platform.scheduler)

}

/** Real Vault's own `Economy` class, below 1.15.2: xMoney's economy under it too, through a proxy. Only loaded with Vault.jar. */
@Suppress("DEPRECATION")
internal object ForeignVault {
    private const val ECONOMY = "net.milkbowl.vault.economy.Economy"
    private const val RESPONSE = "net.milkbowl.vault.economy.EconomyResponse"

    // Vault.jar's own Economy class, if it's another than ours: the economy under it too.
    @Suppress("UNCHECKED_CAST")
    fun register(vault: Plugin, legacy: LegacyEconomy, plugin: Plugin, platform: BukkitPlatform): Pair<Class<Any>, Any>? {
        val type = try {
            Class.forName(ECONOMY, false, vault.javaClass.classLoader) as Class<Any>
        } catch (e: ReflectiveOperationException) {
            return null
        } catch (e: LinkageError) {
            return null
        }
        if (type === net.milkbowl.vault.economy.Economy::class.java) return null
        val response = try {
            Class.forName(RESPONSE, false, type.classLoader)
        } catch (e: ReflectiveOperationException) {
            return null
        }
        val responseType = Class.forName("$RESPONSE\$ResponseType", false, type.classLoader)
        val make = response.getConstructor(Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, responseType, String::class.java)
        val ours = HashMap<Method, Method>()
        for (method in type.methods) {
            try {
                ours[method] = net.milkbowl.vault.economy.Economy::class.java.getMethod(method.name, *method.parameterTypes)
            } catch (e: NoSuchMethodException) {
                // A method our copy lacks (a newer Vault): answered as a refusal below.
            }
        }
        val proxy = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
            when (method.name) {
                "equals" -> return@newProxyInstance self === args?.firstOrNull()
                "hashCode" -> return@newProxyInstance System.identityHashCode(self)
                "toString" -> return@newProxyInstance "xMoney's economy for ${vault.name}'s Vault classes"
            }
            val target = ours[method] ?: return@newProxyInstance defaultOf(method.returnType)
            val result = try {
                target.invoke(legacy, *args.orEmpty())
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
            if (result is net.milkbowl.vault.economy.EconomyResponse) {
                @Suppress("UNCHECKED_CAST")
                val kind = java.lang.Enum.valueOf(responseType as Class<out Enum<*>>, result.type.name)
                make.newInstance(result.amount, result.balance, kind, result.errorMessage)
            } else {
                result
            }
        }
        Bukkit.getServicesManager().register(type, proxy, plugin, ServicePriority.Highest)
        platform.log.info("Vault ${vault.description.version} is installed: plugins find xMoney's economy through its classes too.")
        return type to proxy
    }

    private fun defaultOf(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Double.TYPE -> 0.0
        else -> null
    }
}

/** Which jars in plugins/ are Vault's: read only when one's name says it might be. */
internal object VaultJars {
    /** The ones of [names] in [folder] whose plugin is called Vault. */
    fun named(folder: Path, names: List<String>): List<String> = names.filter { isVault(folder.resolve(it)) }.sorted()

    private fun isVault(jar: Path): Boolean = try {
        ZipFile(jar.toFile()).use { zip ->
            for (descriptor in listOf("plugin.yml", "paper-plugin.yml")) {
                val entry = zip.getEntry(descriptor) ?: continue
                val line = zip.getInputStream(entry).bufferedReader().useLines { lines -> lines.firstOrNull { it.startsWith("name:") } }
                if (line != null) return@use line.substring(5).trim().trim('"', '\'') == "Vault"
            }
            false
        }
    } catch (e: java.io.IOException) {
        false
    }
}

/**
 * Another economy's balances through Vault, for `/xmoney import vault`: VaultUnlocked 2's account
 * list if it has one, otherwise Vault 1 asked about every player who has played. On the server's
 * main thread, a few milliseconds a tick (the other plugin may need it), the list of players read
 * off the server's threads.
 */
@Suppress("DEPRECATION")
internal class VaultImport(private val services: VaultServices, private val scheduler: co.xenastudios.core.common.scheduler.Scheduler) : BalanceSource {
    @Volatile
    private var provider = "the economy registered with Vault"

    override val name: String get() = provider

    override fun read(): Pending<BalanceSource.Read> {
        val done = newPending<BalanceSource.Read>()
        later { start(done) }
        return done
    }

    private fun <T : Any> other(type: Class<T>): T? =
        Bukkit.getServicesManager().getRegistrations(type).map { it.provider }.firstOrNull { !services.ours(it) }

    private fun start(done: Pending<BalanceSource.Read>) {
        val unlocked = other(net.milkbowl.vault2.economy.Economy::class.java)
        val legacy = other(net.milkbowl.vault.economy.Economy::class.java)
        if (unlocked == null && legacy == null) {
            done.resolve(BalanceSource.Read(emptyList(), missing = "no other economy plugin is registered with Vault. Install the old one beside xMoney for the import, then remove it."))
            return
        }
        val found = ArrayList<BalanceSource.Found>()
        if (unlocked != null) {
            provider = unlocked.name
            // Copied, since it may change while it's read; a provider may leave names out.
            val accounts = HashMap(unlocked.uuidNameMap).entries.toList().iterator()
            later {
                step(done, found, accounts) { (id, name) ->
                    if (id != null) found += BalanceSource.Found(PlayerRef.of(id.toKotlin()), name.orEmpty(), true, unlocked.getBalance(XMoneyPlugin.NAME, id).toPlainString())
                }
            }
        } else {
            provider = legacy!!.name
            // Every player file in the worlds: disk work, so off the server's threads.
            scheduler.run(Where.Async) {
                val players: Iterator<OfflinePlayer> = try {
                    Bukkit.getOfflinePlayers().toList().iterator()
                } catch (e: RuntimeException) {
                    done.resolve(BalanceSource.Read(emptyList(), failed = "couldn't list the players who have played here: $e"))
                    return@run
                }
                later {
                    step(done, found, players) { player ->
                        if (legacy.hasAccount(player)) {
                            found += BalanceSource.Found(PlayerRef.of(player.uniqueId.toKotlin()), player.name.orEmpty(), true, java.math.BigDecimal.valueOf(legacy.getBalance(player)).toPlainString())
                        }
                    }
                }
            }
        }
    }

    // On the server's global thread, on a later tick, never right away.
    private fun later(work: () -> Unit) {
        scheduler.run(Where.Global, delay = co.xenastudios.core.common.scheduler.TICK) { work() }
    }

    // A slice now (5 ms), the rest on later ticks.
    private fun <T> step(done: Pending<BalanceSource.Read>, found: MutableList<BalanceSource.Found>, left: Iterator<T>, one: (T) -> Unit) {
        try {
            val until = System.nanoTime() + 5_000_000
            while (left.hasNext() && System.nanoTime() < until) one(left.next())
        } catch (e: RuntimeException) {
            done.resolve(BalanceSource.Read(emptyList(), failed = "$provider failed while being read: $e"))
            return
        }
        if (left.hasNext()) later { step(done, found, left, one) } else done.resolve(BalanceSource.Read(found))
    }
}
