package co.xenastudios.core.common

public actual inline fun <T> locked(lock: Any, block: () -> T): T = synchronized(lock, block)

public actual fun isFatal(error: Throwable): Boolean = error is VirtualMachineError

public actual fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V> = java.util.concurrent.ConcurrentHashMap()
