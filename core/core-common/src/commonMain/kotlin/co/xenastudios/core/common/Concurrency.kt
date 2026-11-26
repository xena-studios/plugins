package co.xenastudios.core.common

/**
 * Runs [block] holding [lock]'s monitor on the JVM, where the core's state is shared between
 * the server's threads; just runs it on Wasm, which has one thread. For setup and teardown,
 * not hot paths: those read a `@Volatile` snapshot instead. Public for plugins' shared code, which
 * has the same threads (zChat's hooks). Inline, so a call makes no lambda, which the JVM would
 * make a class of (decisions/2026-11-25-a-lighter-core.md).
 */
public expect inline fun <T> locked(lock: Any, block: () -> T): T

/**
 * Whether [error] means the process itself is in trouble (out of memory, a stack overflow), so
 * containing it would only hide that: the core lets these through and contains the rest.
 */
public expect fun isFatal(error: Throwable): Boolean

/**
 * A map safe to read and write from many threads at once, without a lock: for state a plugin
 * reads on hot paths from many threads (chat, Folia's regions, Vault's callers). On the JVM a
 * `ConcurrentHashMap`; on Wasm, which has one thread, a plain map. From zChat, where it began,
 * now that xMoney needs one too.
 */
public expect fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V>
