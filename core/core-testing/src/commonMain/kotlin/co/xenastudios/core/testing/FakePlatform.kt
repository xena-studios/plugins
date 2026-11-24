package co.xenastudios.core.testing

import co.xenastudios.core.common.platform.Capability
import co.xenastudios.core.common.platform.Platform
import co.xenastudios.core.common.platform.Threading
import kotlin.time.Instant

/**
 * A whole [Platform] for tests, deterministic on the JVM and on Wasm: a [VirtualScheduler] whose
 * time moves when the test says, a [FakeClock] on that time, files in [MemoryStorage] and a
 * [RecordingLog]. It's a development build, so code on the wrong thread throws.
 *
 * [family] is the server's `plugins/Xena/` and family board: share one [FakeFamily] between two
 * platforms to test two plugins on one server.
 *
 * Folia's [Threading.REGIONS] by default, the strictest: code right there is right on every
 * platform. Pass [Threading.MAIN_THREAD] or [Threading.SINGLE] to test as Bukkit or Pumpkin.
 *
 * ```kotlin
 * val platform = FakePlatform()
 * val modules = ModuleSet(platform)
 * modules.sync(mapOf("autosave" to { AutoSave(platform.storage) }))
 * platform.scheduler.advance(5.minutes)
 * ```
 */
public class FakePlatform(
    threading: Threading = Threading.REGIONS,
    override val capabilities: Set<Capability> = setOf(Capability.SHARED_API),
    override val name: String = "Fake",
    override val development: Boolean = true,
    start: Instant = Instant.parse("2026-01-01T00:00:00Z"),
    /** The family's folder and board, which plugins on one server share. */
    public val family: FakeFamily = FakeFamily(),
) : Platform {
    override val threading: Threading = threading
    override val scheduler: VirtualScheduler = VirtualScheduler(threading)
    override val clock: FakeClock = FakeClock(scheduler, start)
    override val storage: MemoryStorage = MemoryStorage()
    override val log: RecordingLog = RecordingLog()
}
