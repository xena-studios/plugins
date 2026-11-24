package co.xenastudios.core.jvm.config

import co.xenastudios.core.common.config.ConfigGroup
import co.xenastudios.core.common.config.ConfigSchema
import co.xenastudios.core.common.config.ConfigSection
import kotlin.time.Duration.Companion.hours

enum class Mode { LOUD, QUIET }

/** A small config at version 2, whose version 1 called `name` `shop-name`. */
object ShopConfig : ConfigSchema(2, "Shop") {
    val name = string("name", "Shop", "The shop's name.")
    val mode = enum("mode", Mode.LOUD, Mode.entries)
    val timing: Timing = Timing(this)
    val words = stringList("words", listOf("hello"))

    class Timing(parent: ConfigGroup) : ConfigSection(parent, "timing", "When it's open.") {
        val openFor = duration("open-for", 8.hours)
        val max = int("max", 10, min = 1)
    }

    init {
        migrate(1) { move("shop-name", "name") }
    }
}
