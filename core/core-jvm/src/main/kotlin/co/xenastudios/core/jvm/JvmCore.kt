package co.xenastudios.core.jvm

/**
 * The core's JVM side: the bridge to each server's text, JDBC, the library loader, Cloud and
 * the importers will live here (ROADMAP.md, Architecture). Empty for now.
 */
public object JvmCore {
    /** The Java bytecode level this module and `core-common` are compiled to: Java 8's class files. */
    public const val CLASS_FILE_MAJOR: Int = 52
}
