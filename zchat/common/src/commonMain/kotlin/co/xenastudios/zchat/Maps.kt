package co.xenastudios.zchat

/**
 * A map safe to read and write from many threads at once, without a lock: chat arrives on many
 * threads together (Folia's especially), and zChat's state is read on every message. On the JVM
 * a `ConcurrentHashMap`; on Wasm, which has one thread, a plain map.
 */
internal expect fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V>
