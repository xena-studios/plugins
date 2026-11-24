package co.xenastudios.core.api

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** On the JVM, a [Pending] is a `CompletableFuture`, so Java sees nothing new. */
public actual typealias Pending<T> = CompletableFuture<T>

@JvmSynthetic
public actual fun <T> newPending(): Pending<T> = CompletableFuture()

@JvmSynthetic
public actual fun <T> pendingOf(value: T): Pending<T> = CompletableFuture.completedFuture(value)

@JvmSynthetic
public actual fun <T> pendingFailure(error: Throwable): Pending<T> =
    CompletableFuture<T>().also { it.completeExceptionally(error) }

@JvmSynthetic
public actual fun <T> Pending<T>.resolve(value: T): Boolean = complete(value)

@JvmSynthetic
public actual fun <T> Pending<T>.reject(error: Throwable): Boolean = completeExceptionally(error)

public actual val Pending<*>.isSettled: Boolean
    @JvmSynthetic get() = isDone

@JvmSynthetic
public actual fun <T> Pending<T>.onSettled(callback: (value: T?, error: Throwable?) -> Unit) {
    whenComplete { value, error ->
        // A stage after a failed one sees the failure wrapped; callers want the cause.
        val cause = if (error is CompletionException && error.cause != null) error.cause else error
        callback(if (cause == null) value else null, cause)
    }
}
