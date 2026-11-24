package co.xenastudios.core.common.scheduler

import co.xenastudios.core.common.platform.Platform

/**
 * Thrown by [checkThread] in a development build when code touches something its thread
 * doesn't own: the bug Folia would crash on (or worse, not crash on) in production.
 */
public class WrongThreadException(message: String) : IllegalStateException(message)

/**
 * In a [development][Platform.development] build, throws [WrongThreadException] unless the
 * current thread owns [where]; [what] names what was being done, for the message. Release
 * builds don't check, and don't call [what]: the check costs one field read there
 * (ROADMAP.md, Robust).
 */
public inline fun Platform.checkThread(where: Where, what: () -> String) {
    if (development && !scheduler.owns(where)) {
        throw WrongThreadException("${what()} must run on the thread that owns $where, not this one")
    }
}
