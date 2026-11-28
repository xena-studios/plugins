package co.xenastudios.xlimits

import co.xenastudios.core.api.Pending
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.ImportPage
import co.xenastudios.core.jvm.config.ConfigFile
import co.xenastudios.core.jvm.config.RememberingYaml
import co.xenastudios.core.testing.settled
import co.xenastudios.xlimits.api.SlowAction
import co.xenastudios.xlimits.config.Trigger
import co.xenastudios.xlimits.config.XLimitsConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FarmControl's and Insights' files, as their plugins write them, into xLimits' config.yml. */
class ImportTest {
    private val plugins: Path = Files.createTempDirectory("xlimits-import")
    private val configFile = plugins.resolve("xLimits").resolve("config.yml")

    @AfterTest
    fun clean() {
        plugins.toFile().deleteRecursively()
    }

    private fun write(path: String, text: String) {
        val file = plugins.resolve(path)
        Files.createDirectories(file.parent)
        Files.write(file, text.trimIndent().toByteArray())
    }

    private fun <T> Pending<T>.value(): T = settled()

    private fun records(importer: LimitImporter): List<ImportedLimit> {
        assertTrue(importer.detect().value())
        val page = importer.read(null, 100).value() as Outcome.Ok<ImportPage<ImportedLimit>>
        return page.value.records
    }

    private fun config() = (ConfigFile(XLimitsConfig, configFile, RememberingYaml()).load() as Outcome.Ok).value.config

    @Test
    fun farmControlsRunningProfilesBecomeFarmRulesCapsAndALoadProfile() {
        write(
            "FarmControl/profiles.yml",
            """
            profiles:
              nerf:
                group:
                  types: ["category:animal"]
                  count: 15
                actions: [remove-random-movement, disable-collisions]
              trim-villagers:
                group:
                  types: [villager]
                  count: 11
                actions: [kill]
              pens:
                group:
                  types: [cow, sheep]
                  count: 20
                actions: [disable-breeding]
              mixed:
                group:
                  types: ["category:animal", cow]
                  count: 5
                actions: [disable-collisions]
              not-run:
                group:
                  types: [pig]
                  count: 3
                actions: [kill]
            """,
        )
        write(
            "FarmControl/config.yml",
            """
            world-settings:
              default:
                profiles:
                  proactive: [nerf, trim-villagers, pens, mixed]
                  reactive: [nerf]
                reactive-mode-settings:
                  trigger-mspt-threshold: 40.0
                  untrigger-mspt-threshold: 30.0
            """,
        )
        val importer = LimitImporter.all(plugins, dummy()).first { it.id == "farmcontrol" } as LimitImporter
        val read = records(importer)
        assertEquals(5, read.size)
        assertEquals("it mixes categories and types, which one xLimits group can't", importer.skip(read.single { it.name == "mixed" }))
        for (record in read) if (importer.skip(record) == null) assertTrue(importer.write(record).value() is Outcome.Ok)
        val c = config()
        val animals = c[XLimitsConfig.farms.rules]["animals"]!!
        assertEquals(15, animals.threshold)
        assertEquals(setOf(SlowAction.NO_AI, SlowAction.NO_COLLISIONS), animals.actions)
        assertEquals(10, c[XLimitsConfig.entities.limits]["villager"], "a cap one under its count: xLimits refuses rather than kills")
        assertEquals(listOf("cow", "sheep"), c[XLimitsConfig.entities.groups]["pens"])
        assertEquals(setOf(SlowAction.NO_BREEDING), c[XLimitsConfig.farms.rules]["pens"]!!.actions)
        val reactive = c[XLimitsConfig.profiles.list]["farmcontrol_reactive"]!!
        assertEquals(Trigger.MSPT_ABOVE, reactive.trigger)
        assertEquals(40.0, reactive.on)
        assertEquals(30.0, reactive.off)
        assertTrue(c[XLimitsConfig.profiles.enabled])
        // What the owner had is kept, and importing again changes nothing.
        assertEquals(80, c[XLimitsConfig.entities.limits]["animals"])
        for (record in read) if (importer.skip(record) == null) importer.write(record).value()
        val again = config()
        for (key in listOf(XLimitsConfig.farms.rules, XLimitsConfig.entities.limits, XLimitsConfig.entities.groups, XLimitsConfig.profiles.list)) {
            assertEquals(c[key], again[key])
        }
    }

    @Test
    fun insightsGroupLimitsBecomeBlockCapsAndTheRestIsSkipped() {
        write("Insights/limits/redstone.yml", "limit:\n  name: Redstone\n  type: GROUP\n  limit: 64\n  materials: [REPEATER, COMPARATOR]\n")
        write("Insights/limits/hoppers.yml", "limit:\n  name: Hoppers\n  type: GROUP\n  limit: 30\n  materials: [HOPPER]\n")
        write("Insights/limits/tiles.yml", "limit:\n  name: Tiles\n  type: TILE\n  limit: 256\n")
        write("Insights/limits/beds.yml", "limit:\n  name: Beds\n  type: GROUP\n  regex: true\n  limit: 64\n  materials: [\"^.*_BED\"]\n")
        val importer = LimitImporter.all(plugins, dummy()).first { it.id == "insights" } as LimitImporter
        val read = records(importer)
        assertEquals(4, read.size)
        assertEquals(2, read.count { importer.skip(it) != null })
        for (record in read) if (importer.skip(record) == null) importer.write(record).value()
        val c = config()
        assertEquals(64, c[XLimitsConfig.blocks.limits]["redstone"])
        assertEquals(listOf("repeater", "comparator"), c[XLimitsConfig.blocks.groups]["redstone"])
        assertEquals(30, c[XLimitsConfig.blocks.limits]["hopper"])
        assertEquals(null, c[XLimitsConfig.blocks.groups]["hopper"])
    }

    private fun dummy(): XLimitsPlugin {
        val platform = co.xenastudios.core.testing.FakePlatform()
        val server = FakeLimitsServer(platform.scheduler)
        return XLimitsPlugin(platform, server, { Loaded(XLimitsConfig.defaults, co.xenastudios.core.common.i18n.Translations.of(XLimitsMessages.catalog, emptyMap())) }, server, server)
    }
}
