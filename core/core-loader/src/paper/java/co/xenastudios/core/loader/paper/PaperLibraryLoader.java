package co.xenastudios.core.loader.paper;

import co.xenastudios.core.loader.Library;
import co.xenastudios.core.loader.LibraryCache;
import co.xenastudios.core.loader.LibraryException;
import co.xenastudios.core.loader.Manifest;
import co.xenastudios.core.loader.bukkit.LoaderSettings;
import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.JarLibrary;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * The libraries of a plugin on Paper and Folia (1.19.3 on), where its jar's
 * {@code paper-plugin.yml} names this class as its {@code loader}: each library in the jar's
 * {@value Manifest#RESOURCE} from the family's cache in {@code plugins/Xena/libraries/}
 * (downloaded and checked against its pinned SHA-256 there, as the Bukkit shim does below
 * 1.16.5), handed to Paper as a {@link JarLibrary}.
 *
 * <p>In place of {@code plugin.yml}'s {@code libraries:}, which Paper resolves with Maven's
 * resolver at every boot (about 50 ms of the server thread on Paper 1.21.11, the same for one
 * library or five, DECISIONS.md), and whose class loader rewrites every class it loads. Spigot
 * and Paper before 1.19.3 ignore {@code paper-plugin.yml} and keep reading {@code plugin.yml}.
 *
 * <p>Java 17 class files, as Paper 1.19.3 to 1.20.4 run on 17; Paper's API is compiled
 * against, never shipped.
 */
public final class PaperLibraryLoader implements PluginLoader {

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        long started = System.nanoTime();
        try {
            handOver(classpathBuilder);
        } finally {
            // For the budget check, which the shim tells: this is the server thread's time for the
            // plugin's libraries, as plugin.yml's libraries: lines show it on Spigot.
            if (Boolean.getBoolean("xena.startupTimes")) {
                System.setProperty("xena.startupTimes.loader." + classpathBuilder.getContext().getConfiguration().getName(),
                    Long.toString(System.nanoTime() - started));
            }
        }
    }

    private static void handOver(PluginClasspathBuilder classpathBuilder) {
        ClassLoader own = PaperLibraryLoader.class.getClassLoader();
        File plugins = classpathBuilder.getContext().getDataDirectory().toFile().getParentFile();
        try {
            List<Library> libraries = Manifest.read(own);
            if (libraries.isEmpty()) {
                return;
            }
            LibraryCache cache = LoaderSettings.cache(plugins);
            for (Library library : libraries) {
                LibraryCache.Fetched jar = cache.fetch(library);
                if (jar.downloadedFrom() != null) {
                    classpathBuilder.getContext().getLogger().info("Downloaded " + library + " from " + jar.downloadedFrom()
                        + " into " + cache.folder());
                }
                classpathBuilder.addLibrary(new JarLibrary(jar.jar()));
            }
        } catch (IOException | LibraryException e) {
            // The shim, seeing no stdlib, tries again at onLoad and says why if it can't.
            classpathBuilder.getContext().getLogger().error("Couldn't load the plugin's libraries: " + e.getMessage());
        }
    }
}
