package co.xenastudios.xbackup.store

/**
 * A failure reading or writing a file: on the JVM, `java.io.IOException` itself, so the store's
 * own errors ([CorruptException]) and the JDK's are caught alike.
 */
public expect open class IOException(message: String?, cause: Throwable?) : Exception

/** Something in a repository isn't what it should be: a bad hash, a truncated file, a missing blob. */
public class CorruptException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Not enough free disk space to carry on. */
public class NoSpaceException(message: String) : IOException(message, null)
