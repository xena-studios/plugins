package co.xenastudios.xlimbo.bukkit

import org.bukkit.Location
import org.bukkit.Server
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

// The family's APIs, by reflection on their own classes: each Xena plugin relocates its own core, so
// xTeleport's teleport takes xTeleport's PlayerRef and Location, which xLimbo can't name. Found
// through Bukkit's services manager, by the API's name, once each time the plugin enables.

/** The API another plugin registered in the services manager under the interface named [name], with that interface, or `null`. */
internal fun service(server: Server, name: String): Pair<Class<*>, Any>? {
    val type = server.servicesManager.knownServices.firstOrNull { it.name == name } ?: return null
    val api = server.servicesManager.load(type) ?: return null
    return type to api
}

/**
 * xTeleport 2.0's API, for sending players back with `send-back.via: teleport` and for its `xlimbo`
 * destination. Every call goes through xTeleport's teleport, with no warmup, cooldown or cost
 * (`TeleportOptions.immediate()`), and its safety check.
 */
internal class XTeleportBridge private constructor(
    private val api: Any,
    private val toLocation: Method,
    private val toName: Method,
    private val spawn: Method,
    private val warp: Method,
    private val register: Method,
    private val unregister: Method,
    private val parsePlayer: Method,
    private val makeWorld: Method,
    private val makeLocation: java.lang.reflect.Constructor<*>,
    private val immediate: Any,
    private val providerType: Class<*>,
) {
    private fun ref(player: UUID): Any = parsePlayer.invoke(null, player.toString()) ?: throw IllegalStateException("xTeleport didn't read $player")

    /** Their Location, for a place in a loaded world. */
    fun location(place: Location): Any =
        makeLocation.newInstance(makeWorld.invoke(null, place.world.name), place.x, place.y, place.z, place.yaw, place.pitch)

    /** Teleports [player] to [place]; completes `true` once they're there. */
    fun teleport(player: UUID, place: Location): CompletableFuture<Boolean> = done(toLocation.invoke(api, ref(player), location(place), immediate))

    /** Teleports [player] to xTeleport's `spawn`, a warp (`warp:<name>`), or a destination another plugin registered by name. */
    fun teleport(player: UUID, destination: String): CompletableFuture<Boolean> {
        val target = when {
            destination == "spawn" -> spawn.invoke(api) ?: return CompletableFuture.completedFuture(false)
            destination.startsWith("warp:") -> {
                val found = warp.invoke(api, destination.removePrefix("warp:")) ?: return CompletableFuture.completedFuture(false)
                found.javaClass.getMethod("getLocation").invoke(found)
            }
            else -> return done(toName.invoke(api, ref(player), destination, immediate))
        }
        return done(toLocation.invoke(api, ref(player), target, immediate))
    }

    /**
     * Adds xLimbo's `xlimbo` destination: [where] says where a player (by uuid) goes, or `null` to
     * refuse (a listener cancelled the pre-event, or there's no world yet).
     */
    fun registerDestination(owner: String, name: String, where: (UUID) -> Location?) {
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "destination" -> {
                    val uuid = UUID.fromString(args!![0].toString())
                    CompletableFuture.completedFuture(where(uuid)?.let(::location))
                }
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "xLimbo's $name destination"
                else -> null
            }
        }
        val provider = Proxy.newProxyInstance(providerType.classLoader, arrayOf(providerType), handler)
        register.invoke(api, owner, name, provider)
    }

    fun unregisterAll(owner: String) {
        try {
            unregister.invoke(api, owner)
        } catch (e: ReflectiveOperationException) {
            // xTeleport is stopping too.
        }
    }

    private fun done(result: Any?): CompletableFuture<Boolean> {
        val stage = result as? CompletionStage<*> ?: return CompletableFuture.completedFuture(false)
        return stage.toCompletableFuture().handle { value, error -> error == null && value?.toString() == "TELEPORTED" }
    }

    companion object {
        const val API = "co.xenastudios.xteleport.api.XTeleportApi"

        /** xTeleport's API, if it's enabled here and its API is the one this xLimbo knows; why not otherwise. */
        fun find(server: Server): Pair<XTeleportBridge?, String?> {
            val (type, api) = service(server, API) ?: return null to null
            return try {
                val toLocation = type.methods.first { it.name == "teleport" && it.parameterTypes.size == 3 && it.parameterTypes[1] != String::class.java }
                val toName = type.methods.first { it.name == "teleport" && it.parameterTypes.size == 3 && it.parameterTypes[1] == String::class.java }
                val refType = toLocation.parameterTypes[0]
                val locationType = toLocation.parameterTypes[1]
                val optionsType = toLocation.parameterTypes[2]
                val worldType = locationType.constructors.first { it.parameterTypes.size == 6 }.parameterTypes[0]
                val register = type.methods.first { it.name == "registerDestination" }
                XTeleportBridge(
                    api, toLocation, toName,
                    type.getMethod("spawn"), type.getMethod("warp", String::class.java), register, type.getMethod("unregisterAll", String::class.java),
                    refType.getMethod("parseOrNull", String::class.java),
                    worldType.getMethod("of", String::class.java),
                    locationType.getConstructor(worldType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType),
                    optionsType.getMethod("immediate").invoke(null),
                    register.parameterTypes[2],
                ) to null
            } catch (e: NoSuchElementException) {
                null to "this xTeleport's API is different ($e)"
            } catch (e: ReflectiveOperationException) {
                null to "this xTeleport's API is different ($e)"
            } catch (e: LinkageError) {
                null to e.toString()
            }
        }
    }
}

/** xVanish 1.0's API, for who's hidden from whom: its levels, so staff see vanished players in `/xlimbo list` as they would anywhere. */
internal class XVanishBridge private constructor(
    private val api: Any,
    private val isVanished: Method,
    private val canSee: Method,
    private val parsePlayer: Method,
) {
    fun vanished(player: UUID): Boolean = try {
        isVanished.invoke(api, parsePlayer.invoke(null, player.toString())) == true
    } catch (e: ReflectiveOperationException) {
        false
    }

    fun canSee(viewer: UUID, target: UUID): Boolean = try {
        canSee.invoke(api, parsePlayer.invoke(null, viewer.toString()), parsePlayer.invoke(null, target.toString())) == true
    } catch (e: ReflectiveOperationException) {
        true
    }

    companion object {
        const val API = "co.xenastudios.xvanish.api.XVanishApi"

        fun find(server: Server): XVanishBridge? {
            val (type, api) = service(server, API) ?: return null
            return try {
                val isVanished = type.methods.first { it.name == "isVanished" && it.parameterTypes.size == 1 }
                val canSee = type.methods.first { it.name == "canSee" && it.parameterTypes.size == 2 }
                XVanishBridge(api, isVanished, canSee, isVanished.parameterTypes[0].getMethod("parseOrNull", String::class.java))
            } catch (e: NoSuchElementException) {
                null
            } catch (e: ReflectiveOperationException) {
                null
            } catch (e: LinkageError) {
                null
            }
        }
    }
}
