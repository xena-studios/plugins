package co.xenastudios.core.common

public actual fun <T> locked(lock: Any, block: () -> T): T = synchronized(lock, block)

public actual fun isFatal(error: Throwable): Boolean = error is VirtualMachineError
