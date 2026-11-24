package co.xenastudios.core.common

/**
 * The result of something that can fail in a way the caller should handle: a value, or an
 * error of a type the caller can read. The core returns these instead of throwing, so no
 * exception of ours reaches the server (ROADMAP.md, Robust).
 *
 * Named `Outcome`, not `Result`, so it never shadows Kotlin's own `Result`, whose error is
 * always a `Throwable`.
 */
public sealed class Outcome<out T, out E> {

    /** It worked, and here's the [value]. */
    public data class Ok<out T>(public val value: T) : Outcome<T, Nothing>()

    /** It didn't, and here's why: the [error]. */
    public data class Err<out E>(public val error: E) : Outcome<Nothing, E>()

    /** Whether this is an [Ok]. To use the value, check `is Outcome.Ok` instead, which smart-casts. */
    public val isOk: Boolean get() = this is Ok

    /** Whether this is an [Err]. */
    public val isErr: Boolean get() = this is Err

    /** The value, or `null` if this is an [Err]. */
    public fun getOrNull(): T? = (this as? Ok)?.value

    /** The error, or `null` if this is an [Ok]. */
    public fun errorOrNull(): E? = (this as? Err)?.error

    public companion object {
        /** An [Ok] holding [value]. */
        public fun <T> ok(value: T): Outcome<T, Nothing> = Ok(value)

        /** An [Err] holding [error]. */
        public fun <E> err(error: E): Outcome<Nothing, E> = Err(error)
    }
}

/** The value, or what [fallback] makes of the error. */
public inline fun <T, E> Outcome<T, E>.getOrElse(fallback: (E) -> T): T = when (this) {
    is Outcome.Ok -> value
    is Outcome.Err -> fallback(error)
}

/** [ok] of the value, or [err] of the error. */
public inline fun <T, E, R> Outcome<T, E>.fold(ok: (T) -> R, err: (E) -> R): R = when (this) {
    is Outcome.Ok -> ok(value)
    is Outcome.Err -> err(error)
}

/** The value changed by [transform]; an error stays as it is. */
public inline fun <T, E, R> Outcome<T, E>.map(transform: (T) -> R): Outcome<R, E> = when (this) {
    is Outcome.Ok -> Outcome.Ok(transform(value))
    is Outcome.Err -> this
}

/** The error changed by [transform]; a value stays as it is. */
public inline fun <T, E, F> Outcome<T, E>.mapError(transform: (E) -> F): Outcome<T, F> = when (this) {
    is Outcome.Ok -> this
    is Outcome.Err -> Outcome.Err(transform(error))
}

/** The next step, which can fail too, run only on a value; the first error wins. */
public inline fun <T, E, R> Outcome<T, E>.andThen(next: (T) -> Outcome<R, E>): Outcome<R, E> = when (this) {
    is Outcome.Ok -> next(value)
    is Outcome.Err -> this
}

/** Runs [action] on the value, if there is one, and returns this unchanged. */
public inline fun <T, E> Outcome<T, E>.onOk(action: (T) -> Unit): Outcome<T, E> {
    if (this is Outcome.Ok) action(value)
    return this
}

/** Runs [action] on the error, if there is one, and returns this unchanged. */
public inline fun <T, E> Outcome<T, E>.onErr(action: (E) -> Unit): Outcome<T, E> {
    if (this is Outcome.Err) action(error)
    return this
}
