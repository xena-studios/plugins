package co.xenastudios.xstats

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.xstats.api.CustomStat
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * The family's stats on the JVM ([Integrations]): xStats subscribes to each Xena plugin's own event
 * bus, through its API (`XVote.api().getEvents()`), by reflection. Each Xena plugin relocates its own
 * core, so its `EventBus`, `PlayerRef` and `EventListener` are types xStats can't name; on Bukkit each
 * plugin also has its own class loader, which [loaderFor] gives (on Fabric they share one). A
 * listener is a [Proxy] of the plugin's own `EventListener`, made only when that plugin is there.
 *
 * Each event becomes an increment (or a set) of a custom stat, which waits in memory for the next
 * save: a busy chat costs a map update per message, never a database write.
 */
public class FamilyHooks(
    private val plugin: XStatsPlugin,
    /** The class loader a plugin's API is in, by the plugin's name, or `null` if it isn't installed and on. */
    private val loaderFor: (String) -> ClassLoader?,
) {
    private class Hooked(val subscriptions: List<Any>, val spec: Integrations.Spec)

    private val hooked = java.util.concurrent.ConcurrentHashMap<String, Hooked>()

    /** Hooks every Xena plugin that's installed and on, and isn't hooked yet. */
    public fun hookAll() {
        for (spec in Integrations.SPECS) hook(spec.plugin)
    }

    /** Hooks [name]'s stats, if it's one of the family's and it's on. Any thread. */
    public fun hook(name: String) {
        val spec = Integrations.SPECS.firstOrNull { it.plugin.equals(name, ignoreCase = true) } ?: return
        if (hooked.containsKey(spec.plugin)) return
        val loader = loaderFor(spec.plugin) ?: return
        val api = api(loader, spec) ?: return
        val stats = Integrations.hook(plugin, spec) ?: return
        try {
            val events = public(api, "getEvents").invoke(api)
            val subscriptions = ArrayList<Any>()
            for ((event, counter) in counters(spec)) {
                subscribe(loader, events, "co.xenastudios.${spec.id}.api.event.$event", Counter(counter, stats, plugin))?.let(subscriptions::add)
            }
            hooked[spec.plugin] = Hooked(subscriptions, spec)
            plugin.log.info("Keeping ${spec.plugin}'s stats: ${spec.stats.joinToString(", ") { spec.id + "_" + it.id }}.")
        } catch (e: ReflectiveOperationException) {
            Integrations.unhook(plugin, spec)
            plugin.log.warn("Couldn't keep ${spec.plugin}'s stats: this ${spec.plugin}'s API is different ($e).")
        } catch (e: LinkageError) {
            Integrations.unhook(plugin, spec)
            plugin.log.warn("Couldn't keep ${spec.plugin}'s stats: $e")
        }
    }

    /** [name] is going away: its subscriptions cancelled and its stats out of use. */
    public fun unhook(name: String) {
        val spec = Integrations.SPECS.firstOrNull { it.plugin.equals(name, ignoreCase = true) } ?: return
        val gone = hooked.remove(spec.plugin) ?: return
        for (subscription in gone.subscriptions) cancel(subscription)
        Integrations.unhook(plugin, spec)
    }

    /** Every hook cancelled, as xStats stops. */
    public fun close() {
        for (name in hooked.keys.toList()) {
            val gone = hooked.remove(name) ?: continue
            for (subscription in gone.subscriptions) cancel(subscription)
        }
        Integrations.clear(plugin)
    }

    /** The plugins hooked now, by name. */
    public val names: Set<String> get() = hooked.keys

    private fun api(loader: ClassLoader, spec: Integrations.Spec): Any? = try {
        val name = if (spec.id == "zchat") "ZChat" else "X" + spec.plugin.substring(1)
        val holder = Class.forName("co.xenastudios.${spec.id}.api.$name", true, loader)
        if (holder.getMethod("isEnabled").invoke(null) == true) holder.getMethod("api").invoke(null) else null
    } catch (e: ReflectiveOperationException) {
        null
    } catch (e: LinkageError) {
        null
    }

    private fun subscribe(loader: ClassLoader, events: Any, eventClass: String, counter: Counter): Any? {
        val type = Class.forName(eventClass, true, loader)
        val eventType = type.getField("TYPE").get(null)
        val subscribe = publicTypes(events.javaClass).flatMap { it.methods.asList() }.first { it.name == "subscribe" && it.parameterTypes.size == 4 }
        val priorities = subscribe.parameterTypes[1].enumConstants
        val monitor = priorities.firstOrNull { (it as Enum<*>).name == "MONITOR" } ?: priorities.last()
        val listenerType = subscribe.parameterTypes[3]
        val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType), counter)
        return subscribe.invoke(events, eventType, monitor, true, listener)
    }

    private fun cancel(subscription: Any) {
        try {
            public(subscription, "cancel").invoke(subscription)
        } catch (e: ReflectiveOperationException) {
            // Its plugin is gone already, and its bus with it.
        }
    }

    /** What one event counts: the event's class name, and how it counts. */
    private fun counters(spec: Integrations.Spec): List<Pair<String, Int>> = when (spec.id) {
        "xvote" -> listOf("VoteRewardedEvent" to VOTE)
        "xmoney" -> listOf("BalanceChangedEvent" to MONEY)
        "xbans" -> listOf("PunishedEvent" to PUNISHED)
        "xteleport" -> listOf("TeleportEvent" to TELEPORT)
        "xrewind" -> listOf("RollbackFinishedEvent" to ROLLBACK)
        "xmarket" -> listOf("ShopBoughtEvent" to BOUGHT, "ShopSoldEvent" to SOLD)
        "zchat" -> listOf("ChatMessageSentEvent" to CHAT)
        else -> emptyList()
    }

    /** A listener on one of the family's buses: reads the event by its getters and counts it. One class told apart by [kind]. */
    private class Counter(private val kind: Int, private val stats: Map<String, CustomStat>, private val plugin: XStatsPlugin) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            when (method.name) {
                "on" -> try {
                    count(args!![0]!!)
                } catch (e: Exception) {
                    plugin.log.warn("Couldn't count a family event: $e")
                }
                "hashCode" -> return System.identityHashCode(proxy)
                "equals" -> return proxy === args?.get(0)
                "toString" -> return "xStats' family listener"
            }
            return null
        }

        private fun count(event: Any) {
            when (kind) {
                VOTE -> player(event, "getPlayer")?.let { Integrations.count(stats, "votes", it, 1) }
                MONEY -> {
                    val player = player(event, "getPlayer") ?: return
                    val balance = units(get(event, "getBalance"))
                    // Ten-thousandths, kept as hundredths: two decimals.
                    if (balance != null) Integrations.set(stats, "balance", player, balance / 100)
                    val change = units(get(event, "getChange")) ?: return
                    val kind = get(event, "getKind")?.toString()
                    if (change > 0 && (kind == "PAY" || kind == "GIVE" || kind == "PLUGIN")) Integrations.count(stats, "earned", player, change / 100)
                }
                PUNISHED -> {
                    if (get(event, "getFromOtherServer") == true) return
                    val punishment = get(event, "getPunishment") ?: return
                    player(punishment, "getTarget")?.let { Integrations.count(stats, "punishments", it, 1) }
                }
                TELEPORT -> player(event, "getPlayer")?.let { Integrations.count(stats, "teleports", it, 1) }
                ROLLBACK -> {
                    val staff = player(event, "getPlayer") ?: return
                    val applied = (get(get(event, "getResult") ?: return, "getApplied") as? Number)?.toLong() ?: return
                    Integrations.count(stats, "rollbacks", staff, applied)
                }
                BOUGHT, SOLD -> {
                    val player = player(event, "getPlayer") ?: return
                    val amount = (get(event, "getAmount") as? Number)?.toLong() ?: return
                    Integrations.count(stats, if (kind == BOUGHT) "bought" else "sold", player, amount)
                }
                CHAT -> player(event, "getPlayer")?.let { Integrations.count(stats, "messages", it, 1) }
            }
        }

        private fun get(target: Any, getter: String): Any? = public(target, getter).invoke(target)

        // The plugin's own PlayerRef, whose text is the uuid.
        private fun player(target: Any, getter: String): PlayerRef? = get(target, getter)?.toString()?.let(PlayerRef::parseOrNull)

        private fun units(amount: Any?): Long? = amount?.let { (get(it, "getUnits") as? Number)?.toLong() }
    }

    private companion object {
        /**
         * [name], a method taking nothing, as a public class or interface of [target]'s declares it:
         * the class itself may be private (a bus's subscription), which reflection can't call into.
         */
        fun public(target: Any, name: String): Method {
            val seen = ArrayList<Class<*>>()
            fun search(type: Class<*>?): Method? {
                if (type == null || type in seen) return null
                seen += type
                if (java.lang.reflect.Modifier.isPublic(type.modifiers)) {
                    try {
                        return type.getMethod(name)
                    } catch (e: NoSuchMethodException) {
                        // Not here; maybe an interface has it.
                    }
                }
                for (each in type.interfaces) search(each)?.let { return it }
                return search(type.superclass)
            }
            return search(target.javaClass) ?: throw NoSuchMethodException("${target.javaClass.name}.$name")
        }

        /** [type], its superclasses and interfaces that are public: where reflection may call a method. */
        fun publicTypes(type: Class<*>): List<Class<*>> {
            val out = LinkedHashSet<Class<*>>()
            fun add(each: Class<*>?) {
                if (each == null || each in out) return
                if (java.lang.reflect.Modifier.isPublic(each.modifiers)) out += each
                for (inner in each.interfaces) add(inner)
                add(each.superclass)
            }
            add(type)
            return out.toList()
        }

        const val VOTE = 0
        const val MONEY = 1
        const val PUNISHED = 2
        const val TELEPORT = 3
        const val ROLLBACK = 4
        const val BOUGHT = 5
        const val SOLD = 6
        const val CHAT = 7
    }
}
