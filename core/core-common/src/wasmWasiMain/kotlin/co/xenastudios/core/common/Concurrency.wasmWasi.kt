package co.xenastudios.core.common

internal actual fun <T> locked(lock: Any, block: () -> T): T = block()

internal actual fun isFatal(error: Throwable): Boolean = false
