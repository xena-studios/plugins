package co.xenastudios.core.common

public actual inline fun <T> locked(lock: Any, block: () -> T): T = block()

public actual fun isFatal(error: Throwable): Boolean = false

public actual fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V> = HashMap()
