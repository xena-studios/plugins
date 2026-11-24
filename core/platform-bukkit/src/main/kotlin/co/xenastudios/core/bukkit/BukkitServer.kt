package co.xenastudios.core.bukkit

import org.bukkit.Server
import org.bukkit.command.CommandSender

/**
 * What a Bukkit-family server is and has, found once at startup by checking which classes it
 * has (ROADMAP.md, Architecture): every path the adapter takes (Folia's schedulers or Bukkit's,
 * Adventure or Spigot's components, which locale API) is picked from this, never per call.
 */
public class BukkitServer internal constructor(
    /** Which family member it is, from the most capable down. */
    public val flavor: Flavor,
    /** Its Minecraft version, from `Bukkit.getBukkitVersion()`. */
    public val version: GameVersion,
    /** What the server calls itself (`Paper`, `Purpur`), or the flavor's name where it says `CraftBukkit` for Spigot too. */
    public val brand: String,
    /** Whether players and the console are Adventure audiences: Paper and Folia from 1.16.5, PandaSpigot. */
    public val adventure: Boolean,
    /** Whether players take Spigot's `BaseComponent[]` (`player.spigot().sendMessage`): Spigot and everything built on it. */
    public val bungeeChat: Boolean,
    /** Whether `Player.getLocale()` and `PlayerLocaleChangeEvent` are there: 1.12 on. Before, Spigot's `player.spigot().getLocale()`. */
    public val localeApi: Boolean,
) {
    /** Bukkit's family, by what each adds to the one before. */
    public enum class Flavor(
        /** How logs and `/<plugin> debug` name it. */
        public val displayName: String,
    ) {
        /** Paper with regions on their own threads: Folia's schedulers, never Bukkit's. */
        FOLIA("Folia"),

        /** Paper and its forks (Purpur, PandaSpigot): Adventure from 1.16.5. */
        PAPER("Paper"),

        /** Spigot: Bukkit's API with `BaseComponent` chat. */
        SPIGOT("Spigot"),

        /** CraftBukkit, Bukkit's own server: legacy `§` text only. */
        CRAFTBUKKIT("CraftBukkit"),
    }

    /** Whether it's [version] or newer. */
    public fun atLeast(major: Int, minor: Int, patch: Int = 0): Boolean = version >= GameVersion(major, minor, patch)

    /** `Paper 1.21.11`, or `Purpur 1.21.11 (Paper)` for a fork. */
    override fun toString(): String =
        if (brand == flavor.displayName) "$brand $version" else "$brand $version (${flavor.displayName})"

    public companion object {
        // Server classes, not API ones: a plugin can't add these, so they say which server it is.
        private const val FOLIA = "io.papermc.paper.threadedregions.RegionizedServer"
        private val PAPER = listOf(
            // 1.19 and later.
            "io.papermc.paper.configuration.Configuration",
            // 1.9 to 1.19.
            "com.destroystokyo.paper.PaperConfig",
            // 1.8.8, as PaperSpigot.
            "org.github.paperspigot.PaperSpigotConfig",
        )
        private const val SPIGOT = "org.spigotmc.SpigotConfig"
        private const val BUNGEE_CHAT = "net.md_5.bungee.api.chat.BaseComponent"
        private const val PLAYER_SPIGOT = "org.bukkit.entity.Player\$Spigot"
        private const val LOCALE_EVENT = "org.bukkit.event.player.PlayerLocaleChangeEvent"
        private const val AUDIENCE = "net.kyori.adventure.audience.Audience"

        /** What [server] is, by the classes it has. */
        @JvmStatic
        public fun detect(server: Server): BukkitServer {
            val loader = server.javaClass.classLoader
            val find = { name: String -> load(name, loader) }
            val audience = find(AUDIENCE)
            return detect(
                exists = { find(it) != null },
                bukkitVersion = server.bukkitVersion,
                name = server.name,
                adventure = audience != null && audience.isAssignableFrom(CommandSender::class.java),
            )
        }

        /** What a server is, from [exists] (whether it has a class), its [bukkitVersion] and its [name]: the rules, apart from the server. */
        internal fun detect(exists: (String) -> Boolean, bukkitVersion: String, name: String, adventure: Boolean): BukkitServer {
            val flavor = when {
                exists(FOLIA) -> Flavor.FOLIA
                PAPER.any(exists) -> Flavor.PAPER
                exists(SPIGOT) -> Flavor.SPIGOT
                else -> Flavor.CRAFTBUKKIT
            }
            return BukkitServer(
                flavor = flavor,
                version = GameVersion.parse(bukkitVersion),
                // Spigot calls itself CraftBukkit.
                brand = if (name.isBlank() || name == "CraftBukkit") flavor.displayName else name,
                adventure = adventure,
                bungeeChat = exists(BUNGEE_CHAT) && exists(PLAYER_SPIGOT),
                localeApi = exists(LOCALE_EVENT),
            )
        }

        private fun load(name: String, loader: ClassLoader?): Class<*>? = try {
            Class.forName(name, false, loader)
        } catch (e: ClassNotFoundException) {
            null
        } catch (e: LinkageError) {
            null
        }
    }
}

/**
 * A Minecraft version, compared part by part: `1.8.8`, `1.21.11`, and `26.3` in the new
 * scheme, which sorts after every `1.x`.
 */
public class GameVersion(
    /** `1`, or the year in the new scheme (`26`). */
    public val major: Int,
    /** `21` in 1.21.11. */
    public val minor: Int,
    /** `11` in 1.21.11; 0 when there's none. */
    public val patch: Int = 0,
) : Comparable<GameVersion> {

    override fun compareTo(other: GameVersion): Int = when {
        major != other.major -> major.compareTo(other.major)
        minor != other.minor -> minor.compareTo(other.minor)
        else -> patch.compareTo(other.patch)
    }

    override fun equals(other: Any?): Boolean = other is GameVersion && compareTo(other) == 0

    override fun hashCode(): Int = (major * 31 + minor) * 31 + patch

    /** `1.21.11`, or `1.21` with no patch. */
    override fun toString(): String = if (patch == 0) "$major.$minor" else "$major.$minor.$patch"

    public companion object {
        private val LEADING = Regex("^(\\d+)\\.(\\d+)(?:\\.(\\d+))?")

        /**
         * The version at the start of [text], such as Bukkit's `1.21.11-R0.1-SNAPSHOT`, or `0.0`
         * when there's none: an unknown server is treated as the oldest, which is the safe way
         * round.
         */
        @JvmStatic
        public fun parse(text: String): GameVersion {
            val match = LEADING.find(text.trim()) ?: return GameVersion(0, 0)
            val (major, minor, patch) = match.destructured
            return GameVersion(major.toInt(), minor.toInt(), patch.toIntOrNull() ?: 0)
        }
    }
}
