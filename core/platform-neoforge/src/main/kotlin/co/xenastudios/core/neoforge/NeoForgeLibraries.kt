package co.xenastudios.core.neoforge

import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.loader.Library
import co.xenastudios.core.loader.LibraryCache
import co.xenastudios.core.loader.LibraryException
import co.xenastudios.core.loader.Manifest
import net.neoforged.fml.loading.FMLPaths
import java.net.URL
import java.net.URLClassLoader
import java.util.function.Consumer

/**
 * The libraries a plugin's mod downloads instead of carrying (its jar's
 * `META-INF/xena/libraries.txt`, written by the build from the `fetched` dependencies and pinned
 * in gradle/library-pins.txt): the YAML parser and what it needs, as on Fabric. Kotlin for Forge
 * brings the stdlib, so it isn't listed.
 *
 * Each jar comes from `config/xena/libraries/` (downloaded the first time, checked against its pin
 * every time). NeoForge builds its game layer, every mod's module, before any mod runs, and has no
 * way to add a jar to it later; but its class loader asks a fallback loader for a class no module
 * has, and lets a mod set that fallback (`setFallbackClassLoader`, public on the loader on every
 * line: securejarhandler's below 1.21.9, FML's own after). So the family's libraries go into one
 * loader of ours, put there as the fallback, which asks the game's loader first (the libraries
 * reach the stdlib and every mod through it), then its jars, then the fallback it replaced. A
 * class nobody has still ends where it did.
 *
 * One such loader serves every Xena mod: mods are made on several threads at once, so the first
 * one makes it under a lock in the system properties (as the family's board is shared,
 * [NeoForgeFamily]), and the others add their missing jars to it through a JDK interface their
 * relocated cores share (`Consumer<URL>`). A library already visible is left alone, so the family
 * shares one copy.
 *
 * Not jar-in-jar: NeoForge would load the newest copy of each nested jar across mods just as
 * well, but every mod would carry the parser's 870 KiB, which the budgets don't allow (ROADMAP.md).
 */
internal object NeoForgeLibraries {
    private const val HELP = "Set a Maven mirror with -Dxena.libraries.mirror=<url>, or put the jar in config/xena/libraries/ by hand."
    private const val LOCK = "xena:libraries.lock"
    private const val LOADER = "xena:libraries"

    /** The shared cache, `config/xena/libraries/`, with `-Dxena.libraries.mirror` as on Bukkit. */
    fun cache(): LibraryCache {
        val mirror = System.getProperty("xena.libraries.mirror")?.takeIf { it.isNotBlank() }
        return LibraryCache(
            FMLPaths.CONFIGDIR.get().resolve(NeoForgeFamily.FOLDER).resolve("libraries"),
            mirror?.split(',')?.map { it.trim() } ?: LibraryCache.DEFAULT_MIRRORS,
            HELP,
        )
    }

    /**
     * Makes the libraries listed in the jar [loader] has (the mod's own, the game's loader)
     * visible. Throws a [LibraryException] saying what the owner can do when one can't be
     * downloaded: the mod can't run without them.
     */
    fun load(log: Log) {
        val loader = NeoForgeLibraries::class.java.classLoader
        val libraries = NeoForgeLibraries::class.java.getResourceAsStream("/" + Manifest.RESOURCE)?.use { Manifest.parse(it) } ?: return
        val lock = System.getProperties().computeIfAbsent(LOCK) { Any() }
        synchronized(lock) {
            val missing = libraries.filter { !visible(it, loader) }
            if (missing.isEmpty()) return
            val cache = cache()
            val shared = shared(loader)
            val added = missing.map { library ->
                val jar = cache.fetch(library)
                if (jar.replacedBadCopy()) log.warn("The copy of $library in ${cache.folder()} didn't match its pinned SHA-256, so it was downloaded again.")
                shared.accept(jar.jar().toUri().toURL())
                if (!visible(library, loader)) throw LibraryException("$library was added to the game's class path, but ${library.probe()} still isn't visible")
                if (jar.downloadedFrom() != null) "$library (downloaded from ${jar.downloadedFrom()}, ${(jar.bytes() + 1023) / 1024} KiB)" else library.toString()
            }
            log.info("Loaded ${added.joinToString(", ")} from ${cache.folder()}")
        }
    }

    // The family's loader: another Xena mod's, or a new one set as the game's fallback.
    private fun shared(game: ClassLoader): Consumer<URL> {
        @Suppress("UNCHECKED_CAST")
        (System.getProperties()[LOADER] as? Consumer<URL>)?.let { return it }
        val setFallback = try {
            game.javaClass.getMethod("setFallbackClassLoader", ClassLoader::class.java)
        } catch (e: NoSuchMethodException) {
            throw LibraryException("NeoForge's class loader (${game.javaClass.name}) has no setFallbackClassLoader, so the downloaded libraries can't be added", e)
        }
        val made = FallbackLoader(game, previousFallback(game))
        setFallback.invoke(game, made)
        System.getProperties()[LOADER] = made
        return made
    }

    // What the game's loader fell back to before: its parent where it has one (FML's own loader,
    // from 1.21.9), else modlauncher's boot layer, which modlauncher sets as every layer's fallback.
    private fun previousFallback(game: ClassLoader): ClassLoader =
        game.parent ?: try {
            Class.forName("cpw.mods.modlauncher.Launcher").classLoader
        } catch (_: ClassNotFoundException) {
            ClassLoader.getPlatformClassLoader()
        }

    private fun visible(library: Library, loader: ClassLoader): Boolean = try {
        Class.forName(library.probe(), false, loader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }

    /**
     * The game's fallback loader: the downloaded jars, whose classes see the game's (the stdlib,
     * Minecraft, every mod) through [game]. A class the game asks for comes from the jars, then
     * from [previous]; one the jars' own code asks for is the game's first.
     */
    private class FallbackLoader(private val game: ClassLoader, private val previous: ClassLoader) :
        URLClassLoader("xena-libraries", arrayOf(), getPlatformClassLoader()), Consumer<URL> {

        // Set while the game's loader is asking: it has no module for the class, so asking it back would loop.
        private val fromGame = ThreadLocal.withInitial { false }

        override fun accept(url: URL) = addURL(url)

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (fromGame.get()) {
                // Defining the class loads its supertypes through here too (the stdlib's, often),
                // and those must go to the game's loader first again.
                fromGame.set(false)
                try {
                    synchronized(getClassLoadingLock(name)) {
                        val found = findLoadedClass(name) ?: try {
                            findClass(name)
                        } catch (_: ClassNotFoundException) {
                            return previous.loadClass(name)
                        }
                        if (resolve) resolveClass(found)
                        return found
                    }
                } finally {
                    fromGame.set(true)
                }
            }
            try {
                return getPlatformClassLoader().loadClass(name)
            } catch (_: ClassNotFoundException) {
            }
            fromGame.set(true)
            try {
                return game.loadClass(name)
            } finally {
                fromGame.set(false)
            }
        }
    }
}
