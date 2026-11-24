package co.xenastudios.core.testing

import co.xenastudios.core.common.config.YamlReader
import co.xenastudios.core.common.family.Family
import co.xenastudios.core.common.family.MemoryBoard

/**
 * The family's side of a fake server: `plugins/Xena/` in memory ([folder]) and the [board] its
 * plugins meet on. Give one to several [FakePlatform]s to test plugins together: each plugin's
 * [Family] reads the same folder and shares the same player cache.
 *
 * ```kotlin
 * val server = FakeFamily()
 * val bans = FakePlatform(family = server)
 * val chat = FakePlatform(family = server)
 * ```
 */
public class FakeFamily {
    /** `plugins/Xena/`, in memory. */
    public val folder: MemoryStorage = MemoryStorage()

    /** The board the server's plugins share. */
    public val board: MemoryBoard = MemoryBoard()

    /** A plugin's view of the family, reading YAML with [reader]. */
    public fun family(reader: YamlReader = TestYaml): Family = Family(folder, board, reader)
}
