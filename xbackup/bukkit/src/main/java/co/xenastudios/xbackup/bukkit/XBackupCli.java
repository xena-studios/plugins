package co.xenastudios.xbackup.bukkit;

import co.xenastudios.core.loader.Libraries;
import co.xenastudios.core.loader.Library;
import co.xenastudios.core.loader.LibraryCache;
import co.xenastudios.core.loader.Manifest;
import co.xenastudios.core.loader.Strategy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.logging.Logger;

/**
 * {@code java -jar xBackup.jar restore ...}: the command-line restore, for a server that's
 * stopped (or won't start). Java 8 and Kotlin-free, as the core's shim is: it puts the Kotlin
 * stdlib on a class path of its own (from the family's cache, {@code plugins/Xena/libraries/},
 * downloaded there if it's missing), then runs the Kotlin command line ({@code cli.Cli}).
 */
public final class XBackupCli {

    private XBackupCli() {}

    public static void main(String[] args) throws Exception {
        Path jar = Paths.get(XBackupCli.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        ClassLoader own = XBackupCli.class.getClassLoader();
        List<Library> libraries = Manifest.read(own);
        LibraryCache cache = new LibraryCache(cacheFolder(jar), LibraryCache.DEFAULT_MIRRORS,
            "Set a mirror with -Dxena.libraries.mirror=<a Maven repository's address>.");
        Logger log = Logger.getLogger("xBackup");
        ClassLoader loader = Libraries.load(libraries, own, jar.toUri().toURL(), cache, co.xenastudios.core.loader.JavaVersion.current(),
            Strategy.ISOLATED, log).loader();
        Method main = Class.forName("co.xenastudios.xbackup.cli.Cli", true, loader).getMethod("main", String[].class, Path.class);
        int code;
        try {
            code = (Integer) main.invoke(null, args, jar);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
        }
        System.exit(code);
    }

    // The family's library cache: plugins/Xena/libraries on Bukkit, config/xena/libraries on
    // Fabric, in the server folder the jar is in (plugins/ or mods/), else the one it's run from.
    private static Path cacheFolder(Path jar) {
        Path folder = jar.getParent();
        String name = folder != null && folder.getFileName() != null ? folder.getFileName().toString() : "";
        Path server = (name.equals("plugins") || name.equals("mods")) && folder.getParent() != null
            ? folder.getParent() : Paths.get("").toAbsolutePath();
        Path fabric = server.resolve("config").resolve("xena");
        if (name.equals("mods") || !Files.isDirectory(server.resolve("plugins")) && Files.isDirectory(fabric)) {
            return fabric.resolve("libraries");
        }
        return server.resolve("plugins").resolve("Xena").resolve("libraries");
    }
}
