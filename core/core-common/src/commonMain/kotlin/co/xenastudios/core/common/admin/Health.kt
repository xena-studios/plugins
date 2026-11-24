package co.xenastudios.core.common.admin

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.newPending
import co.xenastudios.core.api.onSettled
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.api.resolve
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.db.Database
import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageKey
import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.Threading
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.async
import co.xenastudios.core.common.scheduler.global

/**
 * What one health check found: whether it's fine ([status]), and a [message] saying so in the
 * reader's language, with its [placeholders]. A plugin's own checks use its own messages.
 */
public class Health(
    public val status: Status,
    public val message: MessageKey,
    public val placeholders: Map<String, Any> = emptyMap(),
) {
    /** How it went. */
    public enum class Status {
        /** Fine. */
        OK,

        /** Working, but worth a look: an optional dependency missing, a config problem worked around. */
        WARNING,

        /** Broken: the plugin, or part of it, doesn't work. */
        FAILED,
    }

    /** The message in English, as the report has it. */
    public fun english(): String = AdminText.plainEnglish(message, placeholders)

    override fun toString(): String = "$status ${english()}"
}

/**
 * One of `/<plugin> debug`'s checks: its [name], for the report and for a check that doesn't
 * finish, and what it does. [check] is called on the thread the command runs on and must not
 * wait there: work that waits (a database query) goes through a [Pending]. [HealthChecks] has
 * the core's.
 */
public class HealthCheck(
    public val name: String,
    private val check: () -> Pending<Health>,
) {
    /** Runs the check. One that throws is reported as failed, not passed on. */
    public fun run(): Pending<Health> = try {
        check()
    } catch (e: Throwable) {
        if (isFatal(e)) throw e
        pendingOf(broken(name, "it threw ${e::class.simpleName}: ${e.message}"))
    }

    override fun toString(): String = name

    internal companion object {
        fun broken(name: String, reason: String): Health =
            Health(Health.Status.FAILED, CoreMessages.debugCheckBroken, mapOf("check" to name, "reason" to reason))
    }
}

/**
 * A plugin or mod the plugin works with: whether it's [present], and whether the plugin
 * [required]s it or only does more with it. The adapter fills these in from the server.
 */
public class Dependency(public val name: String, public val present: Boolean, public val required: Boolean = false)

/** The core's health checks, which every plugin's `/<plugin> debug` runs those of that apply. */
public object HealthChecks {

    /**
     * The database answers a query, and how fast, on a reader. [database] gives the one open
     * now (`null` while there's none, such as when it failed to open: that's a failure too).
     */
    public fun database(platform: Platform, database: () -> Database?): HealthCheck = HealthCheck("database") {
        val db = database()
        if (db == null) {
            pendingOf(Health(Health.Status.FAILED, CoreMessages.debugDatabaseFailed, mapOf("database" to "the database", "reason" to "it isn't open")))
        } else {
            val start = platform.clock.elapsed()
            val done = newPending<Health>()
            db.read { it.query(db.sql.raw("SELECT 1")) }.onSettled { result, error ->
                done.resolve(
                    when {
                        error != null -> Health(Health.Status.FAILED, CoreMessages.debugDatabaseFailed, mapOf("database" to db.description, "reason" to error.toString()))
                        result is Outcome.Err -> Health(Health.Status.FAILED, CoreMessages.debugDatabaseFailed, mapOf("database" to db.description, "reason" to result.error.toString()))
                        else -> Health(Health.Status.OK, CoreMessages.debugDatabaseOk, mapOf(
                            "database" to db.description,
                            "millis" to (platform.clock.elapsed() - start).inWholeMilliseconds,
                        ))
                    },
                )
            }
            done
        }
    }

    /** The plugins and mods in [dependencies] are there: a required one missing fails, an optional one warns. */
    public fun dependencies(dependencies: () -> List<Dependency>): HealthCheck = HealthCheck("dependencies") {
        val all = dependencies()
        val missing = all.filter { !it.present && it.required }
        val optional = all.filter { !it.present && !it.required }
        pendingOf(
            when {
                missing.isNotEmpty() -> Health(Health.Status.FAILED, CoreMessages.debugDependenciesMissing, mapOf("names" to missing.joinToString(", ") { it.name }))
                optional.isNotEmpty() -> Health(Health.Status.WARNING, CoreMessages.debugDependenciesOptional, mapOf("names" to optional.joinToString(", ") { it.name }))
                else -> Health(Health.Status.OK, CoreMessages.debugDependenciesOk, mapOf(
                    "names" to all.filter { it.present }.joinToString(", ") { it.name }.ifEmpty { "none needed" },
                ))
            },
        )
    }

    /**
     * Tasks run where they should: one on the global thread finds it owns it, and one sent off
     * it from there finds it doesn't (except on Pumpkin, where everything shares one thread).
     * What goes wrong on Folia when a scheduler is picked wrong at startup, or a fork changed
     * its threads, shows up here first; a task that never runs fails it after the debug
     * command's timeout.
     */
    public fun threading(platform: Platform): HealthCheck = HealthCheck("threading") {
        val done = newPending<Health>()
        fun fail(reason: String) = done.resolve(Health(Health.Status.FAILED, CoreMessages.debugThreadingFailed, mapOf("reason" to reason)))
        platform.scheduler.global {
            if (!platform.scheduler.owns(Where.Global)) {
                fail("a task on the global thread doesn't own it")
                return@global
            }
            platform.scheduler.async {
                if (platform.threading != Threading.SINGLE && platform.scheduler.owns(Where.Global)) {
                    fail("an async task ran on the global thread")
                } else {
                    done.resolve(Health(Health.Status.OK, CoreMessages.debugThreadingOk, mapOf("threading" to describe(platform.threading))))
                }
            }
        }
        done
    }

    /**
     * The config and language files were read without problems the last time ([problems]:
     * each already logged, in English).
     */
    public fun config(problems: () -> List<String>): HealthCheck = HealthCheck("config") {
        val found = problems()
        pendingOf(
            if (found.isEmpty()) Health(Health.Status.OK, CoreMessages.debugConfigOk)
            else Health(Health.Status.WARNING, CoreMessages.debugConfigProblems, mapOf("count" to found.size, "first" to found.first())),
        )
    }

    /** Every module wanted is on; one that failed and turned itself off fails this. */
    public fun modules(modules: ModuleSet): HealthCheck = HealthCheck("modules") {
        val failed = modules.failed
        pendingOf(
            if (failed.isEmpty()) Health(Health.Status.OK, CoreMessages.debugModulesOk, mapOf("count" to modules.active.size))
            else Health(Health.Status.FAILED, CoreMessages.debugModulesFailed, mapOf("modules" to failed.keys.joinToString(", "))),
        )
    }

    internal fun describe(threading: Threading): String = when (threading) {
        Threading.REGIONS -> "Folia's regions"
        Threading.MAIN_THREAD -> "one main thread"
        Threading.SINGLE -> "one thread for everything"
    }
}
