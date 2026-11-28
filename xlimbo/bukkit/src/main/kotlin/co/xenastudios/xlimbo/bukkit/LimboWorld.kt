package co.xenastudios.xlimbo.bukkit

import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.platform.Log
import co.xenastudios.xlimbo.config.XLimboConfig
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

/** What `world:` says, read once per sync. */
internal class WorldSettings(
    val name: String,
    val floor: Material,
    val floorY: Int,
    val autoSave: Boolean,
    val resetOnStartup: Boolean,
    val borderSize: Int,
    val spawnRadius: Int,
    val viewDistance: Int,
    val simulationDistance: Int,
) {
    /** The floor it makes, as the marker keeps it: 2.x's form, so 3.0 takes over 2.x's saved world as it is. */
    val shape: String get() = "${floor.key}@$floorY"

    companion object {
        fun of(config: Config, log: Log): WorldSettings {
            val w = XLimboConfig.world
            val named = config[w.floorBlock]
            val floor = Generators.floor(named) ?: Generators.DEFAULT_BLOCK.also {
                log.warn("config.yml: world.floor-block must be a solid block that doesn't fall, not $named; using ${it.key.key}.")
            }
            return WorldSettings(
                config[w.worldName], floor, config[w.floorY], config[w.autoSave], config[w.resetOnStartup],
                config[w.border.size], config[w.border.spawnRadius], config[w.viewDistance], config[w.simulationDistance],
            )
        }
    }
}

/**
 * The limbo world: made on startup, kept matching the config, and rebuilt when its name or floor
 * changes.
 *
 * xLimbo only ever deletes, unloads or rebuilds a world it made itself, which it marks with a
 * `xlimbo.dat` file. A world it didn't make, such as the server's main world given xLimbo's
 * generator in bukkit.yml, is used as it is and never touched
 * (decisions/2026-10-22-xlimbo-is-paper-only.md).
 *
 * Every method runs on the server thread, except [spawn], [world], [isLimbo] and [floorY], which
 * any thread may call.
 */
internal class LimboWorld(private val plugin: Plugin, private val log: Log) {
    /**
     * Where arrivals go, published whole so a player logging in during a rebuild never sees half of it.
     * [owned]: whether xLimbo made the world, and so may rebuild it; [shape] the floor it was made with.
     */
    class Spot(val world: World, val floorY: Int, val radius: Int, val owned: Boolean, val shape: String)

    @Volatile
    private var spot: Spot? = null

    private var warnedAbout: String? = null

    /** Whether a world folder without xLimbo's marker is still xLimbo's: true just after updating from 1.x, which made its world without one. */
    var adoptUnmarked: Boolean = false

    // Bumped whenever the spawn area changes, so stale chunk loads add no tickets. Server thread only.
    private var readyGeneration = 0

    /** Set while xLimbo itself moves players out of and back into a world it's rebuilding, so those moves aren't arrivals. */
    @Volatile
    var moving: Boolean = false
        private set

    /** The world, or `null` before it's made. */
    val world: World? get() = spot?.world

    fun isLimbo(other: World?): Boolean {
        val current = spot ?: return false
        return other != null && other == current.world
    }

    val floorY: Int get() = spot?.floorY ?: Int.MIN_VALUE

    /** A random spot on the floor within the spawn radius, or `null` while there's no world. Any thread. */
    fun spawn(): Location? {
        val current = spot ?: return null
        val radius = current.radius
        val random = ThreadLocalRandom.current()
        val x = (if (radius == 0) 0 else random.nextInt(-radius, radius + 1)) + 0.5
        val z = (if (radius == 0) 0 else random.nextInt(-radius, radius + 1)) + 0.5
        return Location(current.world, x, current.floorY + 1.0, z, random.nextFloat() * 360f - 180f, 0f)
    }

    /** Makes the server's limbo world match the settings: creating, rebuilding, switching or adjusting it. */
    fun sync(settings: WorldSettings) {
        val current = spot
        val shape = settings.shape
        if (current != null && current.world.name == settings.name) {
            if (current.owned && current.shape != shape) {
                rebuildInPlace(current, settings)
            } else {
                if (!current.owned && current.shape != shape) {
                    warnOnce("${settings.name} isn't a world xLimbo made, so its floor can't change while the server runs; restart to apply floor-block and floor-y.")
                }
                publish(current.world, settings, current.owned, current.shape)
            }
            return
        }
        var next = Bukkit.getWorld(settings.name)
        val owned: Boolean
        val madeWith: String
        if (next != null) {
            // Already loaded: the main world with xLimbo's generator, or one we made before.
            val folder = next.worldFolder.toPath()
            owned = WorldFolders.isOurs(folder)
            madeWith = if (owned) WorldFolders.readShape(folder) else shape
            if (generatorFloor(next) == null) {
                warnOnce("world ${settings.name} doesn't use xLimbo's generator, so it has no void floor; give it \"generator: xLimbo\" in bukkit.yml, or pick a new name.")
            }
        } else {
            next = create(settings) ?: return
            owned = true
            madeWith = shape
        }
        publish(next, settings, owned, madeWith)
        if (current != null) {
            leave(current, next)
        } else {
            // Players already online when the world first appears (xLimbo reloaded in place) come in, as they would on joining.
            for (player in Bukkit.getOnlinePlayers()) if (next != player.world) spawn()?.let { player.teleportAsync(it) }
        }
    }

    /**
     * Lets go of a world xLimbo made, for xLimbo being disabled while the server keeps running:
     * players step out to the main world and it's unloaded, so it doesn't keep this instance's
     * generator. A world xLimbo didn't make is left loaded.
     */
    fun letGo() {
        val current = spot ?: return
        spot = null
        ++readyGeneration
        val old = current.world
        old.removePluginChunkTickets(plugin)
        val main = Bukkit.getWorlds().first()
        if (!current.owned || old == main) return
        for (player in old.players.toList()) player.teleport(main.spawnLocation)
        unload(old, old.isAutoSave)
    }

    // Same world, new floor: everyone steps out to the main world while it's remade.
    private fun rebuildInPlace(current: Spot, settings: WorldSettings) {
        val old = current.world
        val staging = Bukkit.getWorlds().first()
        val players = old.players.toList()
        moving = true
        try {
            for (player in players) player.teleport(staging.spawnLocation)
            spot = null
            if (!unload(old, false)) {
                // Still loaded, so it can't be remade: keep it as it is, floor and marker included,
                // rather than record a floor it doesn't have.
                log.warn("Restart to apply floor-block and floor-y.")
                publish(old, settings, current.owned, current.shape)
                moveIn(players)
                return
            }
            val made = create(settings) ?: return
            publish(made, settings, true, settings.shape)
            moveIn(players)
        } finally {
            moving = false
        }
        log.info("Rebuilt world ${settings.name}" + if (players.isEmpty()) "." else " and moved ${players.size} ${if (players.size == 1) "player" else "players"} back.")
    }

    // Switching worlds: players move to the new one, and the old one is let go of if it was ours.
    private fun leave(old: Spot, next: World) {
        val players = old.world.players.toList()
        moving = true
        try {
            for (player in players) spawn()?.let { player.teleport(it) }
        } finally {
            moving = false
        }
        old.world.removePluginChunkTickets(plugin)
        if (old.owned) unload(old.world, old.world.isAutoSave)
        log.info("Switched the limbo to ${next.name}" + if (players.isEmpty()) "." else " and moved ${players.size} ${if (players.size == 1) "player" else "players"} there.")
    }

    private fun moveIn(players: List<Player>) {
        for (player in players) if (player.isOnline) spawn()?.let { player.teleport(it) }
    }

    /**
     * Makes the world: fresh if it's temporary, its floor changed, or it's to reset; from its folder
     * otherwise. Refuses a folder xLimbo didn't make, rather than mix floors into someone else's
     * world or delete it.
     */
    private fun create(settings: WorldSettings): World? {
        val folder = folder(settings.name) ?: run {
            log.error("world.name \"${settings.name}\" can only use letters, digits, _, - and .; pick another.")
            return null
        }
        if (WorldFolders.hasWorld(folder)) {
            if (!WorldFolders.isOurs(folder) && adoptUnmarked) {
                log.info("Taking over ${settings.name}, the world xLimbo 1.x made.")
                try {
                    // Made with the floor its config, now carried forward, describes.
                    WorldFolders.mark(folder, settings.shape)
                } catch (e: IOException) {
                    log.warn("couldn't mark world ${settings.name} as xLimbo's: ${e.message}")
                }
            }
            if (!WorldFolders.isOurs(folder)) {
                log.error(
                    "There's already a world called ${settings.name} that xLimbo didn't make. xLimbo won't touch it: " +
                        "pick another world.name, or load that world and give it xLimbo's generator.",
                )
                return null
            }
            val fresh = !settings.autoSave || settings.resetOnStartup || settings.shape != WorldFolders.readShape(folder)
            if (fresh) delete(folder)
        }
        val made = try {
            WorldCreator(settings.name)
                .generator(VoidGenerator(settings.floorY, settings.floor))
                .environment(World.Environment.NORMAL)
                .generateStructures(false)
                .createWorld()
        } catch (e: RuntimeException) {
            log.error("Couldn't create world ${settings.name}", e)
            return null
        } ?: run {
            log.error("The server wouldn't create world ${settings.name}.")
            return null
        }
        try {
            WorldFolders.mark(made.worldFolder.toPath(), settings.shape)
        } catch (e: IOException) {
            log.warn("couldn't mark world ${settings.name} as xLimbo's: ${e.message}")
        }
        return made
    }

    // Applies the settings that change in place, then publishes where arrivals go.
    private fun publish(target: World, settings: WorldSettings, owned: Boolean, shape: String) {
        val halfBorder = settings.borderSize / 2
        // Keep arrivals at least two chunks inside the border.
        val radius = maxOf(0, minOf(settings.spawnRadius, halfBorder - 32))
        if (radius < settings.spawnRadius) warnOnce("world.border.spawn-radius is too close to the border; using $radius.")
        if (owned) target.isAutoSave = settings.autoSave
        target.worldBorder.setCenter(0.0, 0.0)
        target.worldBorder.size = settings.borderSize.toDouble()
        if (settings.viewDistance >= 2) target.viewDistance = settings.viewDistance
        if (settings.simulationDistance >= 2) target.simulationDistance = settings.simulationDistance
        // Clear skies for as long as the server runs.
        target.setStorm(false)
        target.isThundering = false
        target.clearWeatherDuration = Int.MAX_VALUE
        // The floor the world really has: for one the server loaded, the generator's.
        val floorY = generatorFloor(target) ?: settings.floorY
        val previous = spot
        spot = Spot(target, floorY, radius, owned, shape)
        if (previous == null || previous.world != target || previous.radius != radius) keepSpawnReady(target, radius)
    }

    // Keeps the chunks arrivals land in loaded, so a whole network arriving at once never waits for
    // generation. Capped, since a huge radius would be millions.
    private fun keepSpawnReady(target: World, radius: Int) {
        target.removePluginChunkTickets(plugin)
        // Loads still in flight from an earlier call must not add tickets after this.
        val generation = ++readyGeneration
        val chunks = minOf(MAX_READY_RADIUS, (radius shr 4) + 1)
        val total = (2 * chunks + 1) * (2 * chunks + 1)
        val left = AtomicInteger(total)
        val started = System.nanoTime()
        for (x in -chunks..chunks) {
            for (z in -chunks..chunks) {
                target.getChunkAtAsync(x, z).thenAccept { chunk ->
                    if (generation != readyGeneration) return@thenAccept
                    chunk.addPluginChunkTicket(plugin)
                    if (left.decrementAndGet() == 0) {
                        log.info("World ${target.name} is ready: $total chunks around spawn loaded in ${(System.nanoTime() - started) / 1_000_000} ms.")
                    }
                }
            }
        }
    }

    private fun unload(target: World, save: Boolean): Boolean {
        target.removePluginChunkTickets(plugin)
        if (!Bukkit.unloadWorld(target, save)) {
            log.warn("couldn't unload world ${target.name}; it stays loaded until a restart.")
            return false
        }
        return true
    }

    // Deletes a world folder xLimbo made. Never a loaded world's: the server would write it back.
    private fun delete(folder: Path) {
        try {
            WorldFolders.delete(folder) { Bukkit.getWorld(it) != null }
        } catch (e: IOException) {
            log.warn("couldn't delete the old world ${folder.fileName}: ${e.message}")
        }
    }

    private fun warnOnce(warning: String) {
        if (warning != warnedAbout) {
            warnedAbout = warning
            log.warn(warning)
        }
    }

    companion object {
        /** Chunks kept loaded around spawn reach at most this far, in chunks. */
        private const val MAX_READY_RADIUS = 8

        /**
         * The floor of a world made by xLimbo's generator, or `null` for any other. A generator of the
         * same name from another class loader counts: one left by xLimbo before it was reloaded in
         * place, in a world the server loaded itself and xLimbo can't remake.
         */
        fun generatorFloor(target: World): Int? {
            val generator = target.generator ?: return null
            if (generator is VoidGenerator) return generator.floorY
            if (generator.javaClass.name != VoidGenerator::class.java.name) return null
            return try {
                generator.javaClass.getMethod("floorY").invoke(generator) as? Int
            } catch (e: ReflectiveOperationException) {
                null
            }
        }

        /**
         * Where a world named [name] lives or would live, or `null` for a name that isn't a plain
         * folder name. Minecraft 1.21 keeps each world in its own folder beside the main one; 26.1 and
         * later keep extra worlds inside it, under dimensions/minecraft/.
         */
        private fun folder(name: String): Path? {
            if (!WorldFolders.isPlainName(name)) return null
            Bukkit.getWorld(name)?.let { return it.worldFolder.toPath() }
            return WorldFolders.folder(name, Bukkit.getWorldContainer().toPath(), Bukkit.getWorlds().first().name)
        }
    }
}
