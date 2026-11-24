package co.xenastudios.core.twin

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.admin.AdminText
import co.xenastudios.core.common.admin.Debug
import co.xenastudios.core.common.admin.HealthChecks
import co.xenastudios.core.common.admin.PlayerDataRegistry
import co.xenastudios.core.common.command.AdminCommands
import co.xenastudios.core.common.command.CommandDispatcher
import co.xenastudios.core.common.command.CommandRegistrar
import co.xenastudios.core.common.command.ModuleReloader
import co.xenastudios.core.common.command.commands
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.family.PlayerCache
import co.xenastudios.core.common.module.ModuleSet
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.scheduler.async
import kotlin.uuid.Uuid

/**
 * xTwin: the second Xena plugin of the family test, never released. CI boots it beside xSample
 * (scripts/boot-family.sh), each jar with its own copy of the core relocated to its own package,
 * so nothing of one plugin's core is a class the other knows; they meet only on the family's
 * board, in JDK types, and in the family's folder (DECISIONS.md, "The family").
 *
 * As it starts it records a made-up offline player, [PROBE_NAME], in its own [PlayerCache]. The
 * boot checks (`family-checks.txt`) then show the other plugin sees that cache: xSample's
 * `forget` finds the probe, xTwin's `forget` removes it, and xSample's no longer finds it.
 * `/xtwin family` checks what the console can't: the family's files are where both plugins
 * look, and whatever the platform registers ([platformChecks]).
 */
public class XTwin(
    private val platform: Platform,
    private val family: Family,
    private val cache: PlayerCache,
    private val registrar: CommandRegistrar?,
    about: () -> Map<String, String> = { emptyMap() },
    adminText: AdminText = AdminText.ENGLISH,
    /** What the platform checks of the family, off the server's threads: each problem found, none when it's shared. */
    private val platformChecks: () -> List<String>,
) {
    /** None: xTwin only has its commands. */
    public val modules: ModuleSet = ModuleSet(platform)

    /** `forget` deletes the player from the family's cache, which is all xTwin keeps. */
    private val playerData = PlayerDataRegistry(cache.asLookup()).apply { register("family", cache.asPlayerData()) }

    /** `reload`, `debug` and `forget`. */
    public val admin: AdminCommands = AdminCommands(
        "xtwin", "xTwin", platform,
        ModuleReloader(modules, reloadFiles = ::syncCommands, wanted = { emptyMap() }),
        debug = Debug(platform, "xTwin 1.0.0", listOf(HealthChecks.threading(platform)), about = about),
        playerData = playerData,
        text = adminText,
    )

    private val dispatcher = CommandDispatcher(
        commands {
            root("xtwin", "The family test's second plugin.") {
                literal("family", "Checks the family is shared with the other Xena plugins.") {
                    executes { platform.scheduler.async { checkFamily() } }
                }
                mount(admin)
            }
        },
        platform.clock, platform.log,
    )

    /** Registers the commands and records the probe player in the family's cache. */
    public fun enable() {
        syncCommands()
        cache.joined(PROBE, PROBE_NAME, null)
        cache.left(PROBE)
        platform.log.info("Recorded $PROBE_NAME in the family's player cache")
    }

    /** Unregisters the commands. */
    public fun disable() {
        modules.clear()
        registrar?.clear()
    }

    private fun syncCommands(): List<String> {
        val warnings = registrar?.sync(dispatcher).orEmpty()
        warnings.forEach { platform.log.warn(it) }
        return warnings
    }

    // Logged, not replied: the boot test reads the log, and a failure is an error, which fails it.
    private fun checkFamily() {
        val problems = mutableListOf<String>()
        // The family's config.yml, which the first plugin to start wrote and the other read.
        val config = family.folder.read(Family.FILE)
        if ((config as? Outcome.Ok)?.value == null) problems += "no config.yml in the family's folder ($config)"
        // The player cache's file, saved there after the probe was recorded.
        val saved = family.folder.read(PlayerCache.FILE)
        if ((saved as? Outcome.Ok)?.value?.decodeToString()?.contains(PROBE_NAME) != true) {
            problems += "${PlayerCache.FILE} in the family's folder doesn't have $PROBE_NAME ($saved)"
        }
        if (cache[PROBE]?.name != PROBE_NAME) problems += "the cache lost $PROBE_NAME"
        problems += platformChecks()
        if (problems.isEmpty()) {
            platform.log.info("FAMILY PASS on ${platform.name}: one board, one player cache, one folder")
        } else {
            platform.log.error("FAMILY FAIL on ${platform.name}: " + problems.joinToString("; "))
        }
    }

    public companion object {
        /** The made-up player xTwin records: an offline-mode UUID, never a real account. */
        public val PROBE: PlayerRef = PlayerRef.of(Uuid.parse("00000000-0000-3000-8000-00000000fa11"))

        /** The probe's name, which the boot checks look up through each plugin's `forget`. */
        public const val PROBE_NAME: String = "FamilyProbe"
    }
}
