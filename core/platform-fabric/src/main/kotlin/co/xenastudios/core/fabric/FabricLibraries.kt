package co.xenastudios.core.fabric

import co.xenastudios.core.common.platform.Log
import co.xenastudios.core.loader.Library
import co.xenastudios.core.loader.LibraryCache
import co.xenastudios.core.loader.LibraryException
import co.xenastudios.core.loader.Manifest
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import java.nio.file.Files

/**
 * The libraries a plugin's mod downloads instead of carrying (its jar's
 * `META-INF/xena/libraries.txt`, written by the build from the `fetched` dependencies and
 * pinned in gradle/library-pins.txt): the YAML parser and what it needs, as on Bukkit below
 * 1.16.5. fabric-language-kotlin brings the stdlib, so it isn't listed.
 *
 * Each jar comes from `config/xena/libraries/` (downloaded the first time, checked against its
 * pin every time) and goes onto the game's class path through Fabric Loader's launcher, which
 * Quilt's loader has too, before any class of the plugin needs it. A library whose probe class
 * is already visible (another Xena mod added it) is left alone, so the family shares one copy,
 * as Bukkit's plugins do before 1.16.5.
 *
 * Not jar-in-jar: Fabric would load the newest copy of each nested jar across mods just as well,
 * but every mod would carry the parser's 870 KiB, which the budgets don't allow (DECISIONS.md).
 */
internal object FabricLibraries {
    private const val HELP = "Set a Maven mirror with -Dxena.libraries.mirror=<url>, or put the jar in config/xena/libraries/ by hand."

    /** The shared cache, `config/xena/libraries/`, with `-Dxena.libraries.mirror` as on Bukkit. */
    fun cache(): LibraryCache {
        val mirror = System.getProperty("xena.libraries.mirror")?.takeIf { it.isNotBlank() }
        return LibraryCache(
            FabricLoader.getInstance().configDir.resolve(FabricFamily.FOLDER).resolve("libraries"),
            mirror?.split(',')?.map { it.trim() } ?: LibraryCache.DEFAULT_MIRRORS,
            HELP,
        )
    }

    /**
     * Makes [modId]'s libraries visible. Throws a [LibraryException] saying what the owner can
     * do when one can't be downloaded: the mod can't run without them.
     */
    fun load(modId: String, log: Log) {
        val mod = FabricLoader.getInstance().getModContainer(modId).orElse(null) ?: return
        val list = mod.findPath(Manifest.RESOURCE).orElse(null) ?: return
        val libraries = Files.newInputStream(list).use { Manifest.parse(it) }
        val loader = FabricLibraries::class.java.classLoader
        val missing = libraries.filter { !visible(it, loader) }
        if (missing.isEmpty()) return
        val cache = cache()
        val added = missing.map { library ->
            val jar = cache.fetch(library)
            if (jar.replacedBadCopy()) log.warn("The copy of $library in ${cache.folder()} didn't match its pinned SHA-256, so it was downloaded again.")
            FabricLauncherBase.getLauncher().addToClassPath(jar.jar())
            if (!visible(library, loader)) throw LibraryException("$library was added to the class path, but ${library.probe()} still isn't visible")
            if (jar.downloadedFrom() != null) "$library (downloaded from ${jar.downloadedFrom()}, ${(jar.bytes() + 1023) / 1024} KiB)" else library.toString()
        }
        log.info("Loaded ${added.joinToString(", ")} from ${cache.folder()}")
    }

    private fun visible(library: Library, loader: ClassLoader): Boolean = try {
        Class.forName(library.probe(), false, loader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }
}
