package co.xenastudios.core.api

/**
 * On Wasm, a [Pending] is a promise of our own: there's one thread, so it needs no locks, and
 * callbacks run as soon as it's settled.
 */
public actual open class Pending<T> internal constructor() {
    internal var settled: Boolean = false
    internal var value: T? = null
    internal var error: Throwable? = null
    private var callbacks: MutableList<(T?, Throwable?) -> Unit>? = null

    internal fun settle(value: T?, error: Throwable?): Boolean {
        if (settled) return false
        settled = true
        this.value = value
        this.error = error
        val waiting = callbacks
        callbacks = null
        waiting?.forEach { it(value, error) }
        return true
    }

    internal fun listen(callback: (T?, Throwable?) -> Unit) {
        if (settled) {
            callback(value, error)
        } else {
            (callbacks ?: mutableListOf<(T?, Throwable?) -> Unit>().also { callbacks = it }) += callback
        }
    }

    override fun toString(): String = when {
        !settled -> "Pending"
        error != null -> "Pending(failed: $error)"
        else -> "Pending($value)"
    }
}

public actual fun <T> newPending(): Pending<T> = Pending()

public actual fun <T> pendingOf(value: T): Pending<T> = Pending<T>().also { it.settle(value, null) }

public actual fun <T> pendingFailure(error: Throwable): Pending<T> = Pending<T>().also { it.settle(null, error) }

public actual fun <T> Pending<T>.resolve(value: T): Boolean = settle(value, null)

public actual fun <T> Pending<T>.reject(error: Throwable): Boolean = settle(null, error)

public actual val Pending<*>.isSettled: Boolean get() = settled

public actual fun <T> Pending<T>.onSettled(callback: (value: T?, error: Throwable?) -> Unit) {
    listen(callback)
}
