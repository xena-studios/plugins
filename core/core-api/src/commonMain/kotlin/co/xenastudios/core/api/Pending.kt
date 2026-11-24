package co.xenastudios.core.api

import kotlin.jvm.JvmSynthetic

/**
 * Work that finishes later, such as a database read: a value of [T], or a failure, once it's
 * settled. What an API returns instead of blocking.
 *
 * **On the JVM, this is `java.util.concurrent.CompletableFuture<T>`** (an `actual typealias`),
 * so a Java plugin sees and uses a plain `CompletableFuture`. On Wasm, where there are no
 * threads, it's a small promise of our own. Shared code uses the functions below, which work
 * on both (DECISIONS.md).
 *
 * Callbacks run on the thread that settles it, which on the JVM is often not a server thread:
 * an API that settles off the server's threads says so, and a caller that touches the world
 * moves to the right thread first.
 */
public expect open class Pending<T>

/** Something that will be settled later, by [resolve] or [reject]. */
public expect fun <T> newPending(): Pending<T>

/** Already settled with [value]. */
public expect fun <T> pendingOf(value: T): Pending<T>

/** Already settled with the failure [error]. */
public expect fun <T> pendingFailure(error: Throwable): Pending<T>

/** Settles it with [value], if it isn't settled yet. Returns whether this call settled it. */
public expect fun <T> Pending<T>.resolve(value: T): Boolean

/** Settles it with the failure [error], if it isn't settled yet. Returns whether this call settled it. */
public expect fun <T> Pending<T>.reject(error: Throwable): Boolean

/** Whether it's settled, either way. */
public expect val Pending<*>.isSettled: Boolean

/**
 * Calls [callback] once it's settled, with the value, or `null` and the failure; right away
 * if it already is.
 */
public expect fun <T> Pending<T>.onSettled(callback: (value: T?, error: Throwable?) -> Unit)

/** A [Pending] of what [transform] makes of the value; a failure, or one [transform] throws, is passed on. */
@JvmSynthetic
public fun <T, R> Pending<T>.map(transform: (T) -> R): Pending<R> {
    val next = newPending<R>()
    onSettled { value, error ->
        if (error != null) {
            next.reject(error)
        } else {
            try {
                @Suppress("UNCHECKED_CAST")
                next.resolve(transform(value as T))
            } catch (e: Exception) {
                next.reject(e)
            }
        }
    }
    return next
}
