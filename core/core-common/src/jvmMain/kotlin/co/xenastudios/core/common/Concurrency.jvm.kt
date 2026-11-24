package co.xenastudios.core.common

internal actual fun <T> locked(lock: Any, block: () -> T): T = synchronized(lock, block)

internal actual fun isFatal(error: Throwable): Boolean = error is VirtualMachineError
