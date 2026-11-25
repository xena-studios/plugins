package co.xenastudios.core.loader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * What every platform's Java shim does as its plugin starts, whatever the platform: the Java the
 * plugin needs, the startup classes loaded ahead, the Kotlin version check, and the
 * {@link PluginEntry} made. The shims ({@code BukkitBootstrap}, {@code VelocityBootstrap},
 * {@code BungeeBootstrap}) differ only in how the server hands them the plugin and its libraries.
 */
public final class Startup {

    /**
     * {@code -Dxena.startupTimes=true}: each plugin logs how long its startup took, for the budget
     * checks (scripts/budgets/check.sh, scripts/boot-proxy.sh), which can't tell from the log
     * where one plugin's start ends.
     */
    public static final boolean TIMES = Boolean.getBoolean("xena.startupTimes");

    /**
     * The stdlib's biggest classes, which nearly every plugin needs at once (`listOf` alone loads
     * all of {@code ArraysKt}, 800 KiB of class files): loaded ahead when the jar has no list of
     * its own (DECISIONS.md, "Budgets").
     */
    private static final String[] PRELOAD = {
        "kotlin.collections.ArraysKt", "kotlin.collections.CollectionsKt", "kotlin.collections.MapsKt",
        "kotlin.collections.SetsKt", "kotlin.text.StringsKt", "kotlin.sequences.SequencesKt", "kotlin.ranges.RangesKt",
        "kotlin.text.CharsKt", "kotlin.text.Regex", "kotlin.text.HexExtensionsKt", "kotlin.time.Duration",
        "kotlin.time.DurationKt", "kotlin.uuid.Uuid", "kotlin.uuid.UuidKt", "kotlin.collections.ArrayDeque",
        "kotlin.collections.builders.MapBuilder",
    };

    /** The classes a plugin loads as it starts, in the jar, written by build-logic's R8Shrink. */
    private static final String STARTUP_CLASSES = "META-INF/xena/startup-classes.txt";

    private static final String STDLIB = "org.jetbrains.kotlin:kotlin-stdlib:";

    private Startup() {}

    /**
     * The Java the plugin's Kotlin classes were compiled for, from the class file of
     * {@code entryPoint} in {@code jar}'s loader: the shim checks it before loading anything.
     */
    public static int neededJava(ClassLoader jar, String entryPoint) throws IOException {
        InputStream in = jar.getResourceAsStream(entryPoint.replace('.', '/') + ".class");
        if (in == null) {
            throw new IOException("the jar has no " + entryPoint + ": entryPoint() names a class that isn't there");
        }
        try (InputStream header = in) {
            return Math.max(8, JavaVersion.ofClassFile(header));
        }
    }

    /** Whether the entry point has its own {@link PluginEntry#load()}. */
    public static boolean overridesLoad(Class<?> type) {
        try {
            return type.getMethod("load").getDeclaringClass() != PluginEntry.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** The entry point's public constructor taking {@code plugin} (as its own class, or one it extends). */
    public static PluginEntry construct(Class<?> type, Object plugin) throws Exception {
        if (!PluginEntry.class.isAssignableFrom(type)) {
            throw new ClassCastException(type.getName() + " doesn't implement " + PluginEntry.class.getName());
        }
        for (Constructor<?> constructor : type.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isInstance(plugin)) {
                try {
                    return (PluginEntry) constructor.newInstance(plugin);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    throw cause instanceof Exception ? (Exception) cause : e;
                }
            }
        }
        throw new NoSuchMethodException(type.getName() + " has no public constructor taking the plugin");
    }

    /**
     * Loads and links, without initializing, the classes the plugin needs as it starts, on up to
     * two daemon threads called {@code name}: the jar's {@value #STARTUP_CLASSES} (written at build
     * time from a list recorded on a real server), or {@link #PRELOAD} when the jar has none and
     * uses the stdlib. Class loading is safe from any thread: if the server's thread wants a class
     * one of these is loading, it waits for that class alone, as it would have loaded it itself.
     * Linking is the bytecode verifier's pass over the class, which a class from a plugin's jar
     * gets and which otherwise falls to the first thread that uses it, the server's; nothing in
     * the classes runs, so no listener, task or static state of a module starts here.
     */
    public static void preload(List<Library> libraries, ClassLoader jar, final ClassLoader loader, String name) {
        final List<String> names = startupClasses(jar);
        if (names.isEmpty()) {
            boolean kotlin = false;
            for (Library library : libraries) {
                kotlin |= library.coordinates().startsWith(STDLIB);
            }
            if (!kotlin) {
                return;
            }
            Collections.addAll(names, PRELOAD);
        }
        // Two take the list in turn, in its order, the order the server's thread will want them
        // in: one keeps ahead of it while the other verifies. One on a machine with two cores or
        // fewer, where a second would only take the server's.
        final AtomicInteger next = new AtomicInteger();
        Runnable work = new Runnable() {
            @Override
            public void run() {
                for (int i = next.getAndIncrement(); i < names.size(); i = next.getAndIncrement()) {
                    try {
                        // getDeclaredConstructors links the class (verifies it) without
                        // initializing it, where Class.forName(name, false, ...) only loads it.
                        Class.forName(names.get(i), false, loader).getDeclaredConstructors();
                    } catch (Throwable ignored) {
                        // A class this server doesn't have (another Kotlin release, a platform path
                        // that isn't this one's): the plugin loads what it uses itself.
                    }
                }
            }
        };
        int threads = Runtime.getRuntime().availableProcessors() > 2 ? 2 : 1;
        for (int i = 0; i < threads; i++) {
            Thread thread = new Thread(work, name);
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            thread.start();
        }
    }

    /** The jar's {@value #STARTUP_CLASSES}, one class a line; empty when it has none. */
    private static List<String> startupClasses(ClassLoader jar) {
        List<String> names = new ArrayList<>();
        InputStream in = jar.getResourceAsStream(STARTUP_CLASSES);
        if (in == null) {
            return names;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.trim().isEmpty()) {
                    names.add(line.trim());
                }
            }
        } catch (IOException e) {
            names.clear();
        }
        return names;
    }

    /**
     * Where plugins share one stdlib (Bukkit before 1.16.5, a proxy whose plugins see each
     * other's classes), a plugin built with another Kotlin runs on the first one's. Every Xena
     * plugin pins the same version; this says so when one doesn't, or when another plugin
     * brought its own Kotlin.
     */
    public static void checkKotlin(String plugin, List<Library> libraries, ClassLoader loader, Logger log) {
        for (Library library : libraries) {
            if (!library.coordinates().startsWith(STDLIB)) {
                continue;
            }
            try {
                Class<?> version = Class.forName("kotlin.KotlinVersion", true, loader);
                String current = String.valueOf(version.getField("CURRENT").get(null));
                if (!current.equals(library.version())) {
                    CodeSource source = version.getProtectionDomain().getCodeSource();
                    log.warning(plugin + " was built for Kotlin " + library.version() + ", but Kotlin "
                        + current + " is already loaded" + (source == null ? "" : " from " + source.getLocation())
                        + ". Update every Xena plugin to the same release, and check other plugins for an old Kotlin.");
                }
            } catch (ReflectiveOperationException | LinkageError e) {
                log.warning("Couldn't read the Kotlin version: " + e);
            }
        }
    }
}
