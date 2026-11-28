package co.xenastudios.xplots

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.xplots.model.Box
import co.xenastudios.xplots.reset.Deflate
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/*
 * The family's APIs, from xPlots' side. Each Xena plugin carries its own copy of the core, relocated
 * into its own package, so another plugin's API takes its own core's types (its PlayerRef, its
 * Location), not ours: each is called by name, through the class loader that loaded it, its
 * methods looked up once, and its types made with its own factories. A plugin that isn't there,
 * or whose API doesn't match, is simply not used.
 */

/** Deflate and inflate through `java.util.zip`, as 1.x wrote snapshots (`DeflaterOutputStream`'s zlib stream). */
public object JvmDeflate : Deflate {
    override fun deflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        try {
            deflater.setInput(bytes)
            deflater.finish()
            val out = ByteArrayOutputStream(maxOf(64, bytes.size / 4))
            val buffer = ByteArray(8192)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    override fun inflate(bytes: ByteArray, max: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(maxOf(256, bytes.size * 4))
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buffer)
                } catch (e: DataFormatException) {
                    throw IllegalArgumentException("snapshot isn't deflated data: ${e.message}")
                }
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) throw IllegalArgumentException("snapshot ends too soon")
                out.write(buffer, 0, n)
                require(out.size() <= max) { "snapshot is too big" }
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }
}

/** A player as another plugin's core has them: its `PlayerRef.parseOrNull` of ours, as text. */
private fun foreignRef(parse: Method, player: PlayerRef): Any? = parse.invoke(null, player.toString())

/** Ours, from another plugin's `PlayerRef`: by its text. */
private fun ownRef(foreign: Any?): PlayerRef? = foreign?.toString()?.let { PlayerRef.parseOrNull(it) }

@Suppress("UNCHECKED_CAST")
private fun <T> settle(future: Any?, read: (Any?) -> T, fallback: T): Pending<T> {
    val f = future as? CompletableFuture<Any?> ?: return pendingOf(fallback)
    val out = newPending<T>()
    f.whenComplete { value, error ->
        if (error != null) out.resolve(fallback)
        else try {
            out.resolve(read(value))
        } catch (e: Exception) {
            out.resolve(fallback)
        }
    }
    return out
}

/**
 * Money through xMoney 2.0's API: every payment in its ledger as `xPlots`, with the plot
 * (`/eco history` shows "xPlots: rent for box-12"). xMoney counts in 1/10,000ths; a price crosses
 * as its nearest units. Its ledger is what a renewal cut short by a restart is checked against.
 */
public class XMoneyBridge private constructor(
    private val api: Any,
    private val has: Method,
    private val withdraw: Method,
    private val deposit: Method,
    private val format: Method,
    private val history: Method,
    private val parseRef: Method,
    private val ofUnits: Method,
    private val isSuccess: Method,
) : Economy {
    override val name: String get() = "xMoney"

    private fun amount(value: Double): Any = ofUnits.invoke(null, kotlin.math.round(value * 10_000).toLong())

    override fun has(player: PlayerRef, amount: Double): Boolean = try {
        has.invoke(api, foreignRef(parseRef, player), amount(amount)) == true
    } catch (e: Exception) {
        false
    }

    override fun withdraw(player: PlayerRef, amount: Double, reason: String): Pending<Boolean> = call(withdraw, player, amount, reason)

    override fun deposit(player: PlayerRef, amount: Double, reason: String): Pending<Boolean> = call(deposit, player, amount, reason)

    private fun call(method: Method, player: PlayerRef, amount: Double, reason: String): Pending<Boolean> = try {
        settle(method.invoke(api, XPlotsPlugin.NAME, foreignRef(parseRef, player), amount(amount), reason), { it != null && isSuccess.invoke(it) == true }, false)
    } catch (e: Exception) {
        pendingOf(false)
    }

    override fun format(amount: Double): String? = try {
        format.invoke(api, amount(amount)) as String
    } catch (e: Exception) {
        null
    }

    override fun charged(player: PlayerRef, marker: String): Pending<Boolean?> {
        val out = newPending<Boolean?>()
        page(player, marker, 1, out)
        return out
    }

    // The newest pages first: a renewal cut short is among a player's last payments.
    private fun page(player: PlayerRef, marker: String, page: Int, out: Pending<Boolean?>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val future = history.invoke(api, foreignRef(parseRef, player), page) as CompletableFuture<Any?>
            future.whenComplete { result, error ->
                if (error != null || result == null) return@whenComplete run { out.resolve(null) }
                try {
                    val type = result.javaClass
                    val entries = type.getMethod("getEntries").invoke(result) as List<*>
                    val pages = type.getMethod("getPages").invoke(result) as Int
                    val found = entries.any { e ->
                        val c = e!!.javaClass
                        c.getMethod("getSource").invoke(e) == XPlotsPlugin.NAME && (c.getMethod("getReason").invoke(e) as String).contains(marker)
                    }
                    when {
                        found -> out.resolve(true)
                        page < pages && page < 5 -> page(player, marker, page + 1, out)
                        else -> out.resolve(false)
                    }
                } catch (e: Exception) {
                    out.resolve(null)
                }
            }
        } catch (e: Exception) {
            out.resolve(null)
        }
    }

    public companion object {
        /** xMoney's API, if xMoney is enabled where [loader] can see it; `null` otherwise. */
        @JvmStatic
        public fun find(loader: ClassLoader): XMoneyBridge? = try {
            val entry = Class.forName("co.xenastudios.xmoney.api.XMoney", true, loader)
            if (entry.getMethod("isEnabled").invoke(null) != true) {
                null
            } else {
                val api = entry.getMethod("api").invoke(null)
                val type = Class.forName("co.xenastudios.xmoney.api.XMoneyApi", false, loader)
                val withdraw = type.methods.first { it.name == "withdraw" && it.parameterCount == 4 }
                val deposit = type.methods.first { it.name == "deposit" && it.parameterCount == 4 }
                val ref = withdraw.parameterTypes[1]
                val money = withdraw.parameterTypes[2]
                val result = Class.forName("co.xenastudios.xmoney.api.TransactionResult", false, loader)
                XMoneyBridge(
                    api, type.getMethod("has", ref, money), withdraw, deposit, type.getMethod("format", money),
                    type.getMethod("history", ref, Int::class.javaPrimitiveType),
                    ref.getMethod("parseOrNull", String::class.java), money.getMethod("ofUnits", Long::class.javaPrimitiveType), result.getMethod("isSuccess"),
                )
            }
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            null
        }
    }
}

/**
 * Plot homes in xTeleport 2.0's `/homes`, through its extra-homes seam (`registerHomes`): its
 * `HomeProvider` made as a proxy that asks xPlots, each plot home made as its own `Home`.
 */
public object XTeleportHomes {
    /** Registers [homes] with xTeleport, if it's enabled where [loader] can see it; the handle takes them away. `null` without it. */
    @JvmStatic
    public fun register(loader: ClassLoader, homes: (PlayerRef) -> List<PlotHome>): AutoCloseable? = try {
        val entry = Class.forName("co.xenastudios.xteleport.api.XTeleport", true, loader)
        if (entry.getMethod("isEnabled").invoke(null) != true) {
            null
        } else {
            val api = entry.getMethod("api").invoke(null)
            val type = Class.forName("co.xenastudios.xteleport.api.XTeleportApi", false, loader)
            val providerType = Class.forName("co.xenastudios.xteleport.api.HomeProvider", false, loader)
            val homeType = Class.forName("co.xenastudios.xteleport.api.Home", false, loader)
            val construct = homeType.constructors.first { it.parameterCount == 3 }
            val refType = construct.parameterTypes[0]
            val locationType = construct.parameterTypes[2]
            val parse = refType.getMethod("parseOrNull", String::class.java)
            val makeLocation = locationType.constructors.first { it.parameterCount == 6 }
            val worldType = makeLocation.parameterTypes[0]
            val world = worldType.getMethod("of", String::class.java).let { m -> if (java.lang.reflect.Modifier.isStatic(m.modifiers)) m else null }
                ?: worldType.getField("Companion").get(null).let { companion -> companion.javaClass.getMethod("of", String::class.java) }
            val worldCompanion = if (java.lang.reflect.Modifier.isStatic(world.modifiers)) null else worldType.getField("Companion").get(null)
            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "homes" -> {
                        val ref = ownRef(args!![0])
                        if (ref == null) emptyList<Any>() else homes(ref).map { home ->
                            val l = home.location
                            val where = makeLocation.newInstance(world.invoke(worldCompanion, l.world.name), l.x, l.y, l.z, l.yaw, l.pitch)
                            construct.newInstance(args[0], home.name, where)
                        }
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.get(0)
                    "toString" -> "xPlots' plot homes"
                    else -> null
                }
            }
            val provider = Proxy.newProxyInstance(providerType.classLoader, arrayOf(providerType), handler)
            type.getMethod("registerHomes", String::class.java, providerType).invoke(api, XPlotsPlugin.NAME, provider)
            val unregister = type.getMethod("unregisterAll", String::class.java)
            AutoCloseable {
                try {
                    unregister.invoke(api, XPlotsPlugin.NAME)
                } catch (e: Exception) {
                    // xTeleport went first.
                }
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: LinkageError) {
        null
    }
}

/**
 * Rollbacks inside a plot through xRewind 2.0's API: a query of the player's changes in the
 * plot's box and the last few seconds, planned and applied with xRewind's own budget, as
 * `/xrewind rollback` would, but kept to the plot's blocks.
 */
public class XRewindBridge private constructor(
    private val api: Any,
    private val builder: Method,
    private val planRollback: Method,
    private val apply: Method,
    private val worldOf: Method,
    private val worldCompanion: Any?,
) : Rewind {
    override fun rollback(world: String, box: Box, playerName: String, seconds: Long): Pending<Outcome<Int, String>> {
        val out = newPending<Outcome<Int, String>>()
        try {
            var b = builder.invoke(null)
            val type = b.javaClass
            fun call(name: String, vararg args: Any?): Any {
                val m = type.methods.first { it.name == name && it.parameterCount == args.size }
                return m.invoke(b, *args)
            }
            b = call("users", arrayOf(playerName.lowercase()))
            b = call("time", seconds)
            b = call("area", worldOf.invoke(worldCompanion, world), box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
            val query = call("build")
            @Suppress("UNCHECKED_CAST")
            val planned = planRollback.invoke(api, query) as CompletableFuture<Any?>
            planned.whenComplete { plan, error ->
                if (error != null || plan == null) return@whenComplete run { out.resolve(Outcome.Err((error?.cause ?: error)?.message ?: "it couldn't plan the rollback")) }
                try {
                    val blocks = plan.javaClass.getMethod("getBlocks").invoke(plan) as List<*>
                    val items = plan.javaClass.getMethod("getItems").invoke(plan) as List<*>
                    if (blocks.isEmpty() && items.isEmpty()) return@whenComplete run { out.resolve(Outcome.Ok(0)) }
                    @Suppress("UNCHECKED_CAST")
                    val applied = apply.invoke(api, plan) as CompletableFuture<Any?>
                    applied.whenComplete { result, failed ->
                        if (failed != null || result == null) out.resolve(Outcome.Err((failed?.cause ?: failed)?.message ?: "it couldn't apply the rollback"))
                        else out.resolve(Outcome.Ok(result.javaClass.getMethod("getApplied").invoke(result) as Int))
                    }
                } catch (e: Exception) {
                    out.resolve(Outcome.Err(e.toString()))
                }
            }
        } catch (e: Exception) {
            out.resolve(Outcome.Err(e.toString()))
        }
        return out
    }

    public companion object {
        /** xRewind's API, if xRewind 2.0 is enabled where [loader] can see it; `null` otherwise. */
        @JvmStatic
        public fun find(loader: ClassLoader): XRewindBridge? = try {
            val entry = Class.forName("co.xenastudios.xrewind.api.XRewind", true, loader)
            if (entry.getMethod("isEnabled").invoke(null) != true) {
                null
            } else {
                val api = entry.getMethod("api").invoke(null)
                val type = Class.forName("co.xenastudios.xrewind.api.XRewindApi", false, loader)
                val query = Class.forName("co.xenastudios.xrewind.api.Query", false, loader)
                val builder = query.getMethod("builder")
                val area = builder.returnType.methods.first { it.name == "area" && it.parameterCount == 7 }
                val worldType = area.parameterTypes[0]
                val companion = worldType.getField("Companion").get(null)
                val of = companion.javaClass.getMethod("of", String::class.java)
                val plan = Class.forName("co.xenastudios.xrewind.api.Plan", false, loader)
                XRewindBridge(api, builder, type.getMethod("planRollback", query), type.getMethod("apply", plan), of, companion)
            }
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            null
        }
    }
}

/** Who xVanish hides from whom, through its API's `canSee` (its levels and `xvanish.see`). */
public class XVanishBridge private constructor(private val api: Any, private val canSee: Method, private val parseRef: Method) {
    /** Whether xVanish hides [target] from [viewer]. */
    public fun hidden(viewer: PlayerRef, target: PlayerRef): Boolean = try {
        canSee.invoke(api, foreignRef(parseRef, viewer), foreignRef(parseRef, target)) == false
    } catch (e: Exception) {
        false
    }

    public companion object {
        @JvmStatic
        public fun find(loader: ClassLoader): XVanishBridge? = try {
            val entry = Class.forName("co.xenastudios.xvanish.api.XVanish", true, loader)
            if (entry.getMethod("isEnabled").invoke(null) != true) {
                null
            } else {
                val api = entry.getMethod("api").invoke(null)
                val type = Class.forName("co.xenastudios.xvanish.api.XVanishApi", false, loader)
                val canSee = type.methods.first { it.name == "canSee" && it.parameterCount == 2 }
                XVanishBridge(api, canSee, canSee.parameterTypes[0].getMethod("parseOrNull", String::class.java))
            }
        } catch (e: Exception) {
            null
        } catch (e: LinkageError) {
            null
        }
    }
}
