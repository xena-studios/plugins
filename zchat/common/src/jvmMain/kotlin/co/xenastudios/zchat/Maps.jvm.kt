package co.xenastudios.zchat

internal actual fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V> = java.util.concurrent.ConcurrentHashMap()
