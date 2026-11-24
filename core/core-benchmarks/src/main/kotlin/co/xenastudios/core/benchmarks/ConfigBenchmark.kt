package co.xenastudios.core.benchmarks

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.Config
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.Configs
import co.xenastudios.core.jvm.config.JvmYaml
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.infra.Blackhole
import kotlin.time.Duration.Companion.seconds

internal object CoinsConfig : ConfigSchema(1, "xCoins") {
    val homes: Homes = Homes(this)

    class Homes(parent: co.xenastudios.core.common.config.ConfigGroup) :
        co.xenastudios.core.common.config.ConfigSection(parent, "homes", "Setting and going to homes.") {
        val limit = int("limit", 3, "How many homes a player can set.", min = 0, max = 100)
        val delay = duration("teleport-delay", 3.seconds, "How long a player stands still first.")
        val worlds = stringList("blocked-worlds", listOf("world_nether"), "Worlds homes can't be set in.")
    }
}

/**
 * Reading a setting, which a hot path does on every event (ROADMAP.md: configs become typed
 * objects at load, so a read is a lookup); and loading the file, which happens at startup and
 * on reload, through the JVM's YAML parser.
 */
@State(Scope.Benchmark)
public open class ConfigBenchmark {
    private val text = Configs.defaultText(CoinsConfig)
    private val config: Config = (Configs.load(CoinsConfig, text, JvmYaml) as Outcome.Ok).value.config

    @Benchmark
    public fun get(): Int = config[CoinsConfig.homes.limit]

    // A Duration is an inline class, which would mangle the method's name past what JMH takes.
    @Benchmark
    public fun getDuration(blackhole: Blackhole) {
        blackhole.consume(config[CoinsConfig.homes.delay].inWholeMilliseconds)
    }

    @Benchmark
    public fun load(): Any = Configs.load(CoinsConfig, text, JvmYaml)
}
