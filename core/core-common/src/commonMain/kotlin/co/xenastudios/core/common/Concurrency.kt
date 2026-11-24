package co.xenastudios.core.common

/**
 * Runs [block] holding [lock]'s monitor on the JVM, where the core's state is shared between
 * the server's threads; just runs it on Wasm, which has one thread. For setup and teardown,
 * not hot paths: those read a `@Volatile` snapshot instead.
 */
internal expect fun <T> locked(lock: Any, block: () -> T): T

/**
 * Whether [error] means the process itself is in trouble (out of memory, a stack overflow), so
 * containing it would only hide that: the core lets these through and contains the rest.
 */
internal expect fun isFatal(error: Throwable): Boolean
