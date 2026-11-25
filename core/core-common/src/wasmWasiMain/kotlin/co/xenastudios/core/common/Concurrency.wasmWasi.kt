package co.xenastudios.core.common

public actual fun <T> locked(lock: Any, block: () -> T): T = block()

public actual fun isFatal(error: Throwable): Boolean = false
