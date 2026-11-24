package co.xenastudios.core.common.update

import co.xenastudios.core.common.Outcome
import co.xenastudios.core.common.config.ConfigGroup
import co.xenastudios.core.common.config.ConfigKey
import co.xenastudios.core.common.config.ConfigSection
import co.xenastudios.core.common.config.Config

/**
 * How stable a release is, as Modrinth's version types say it (`release`, `beta`, `alpha`), and
 * the update channel an owner picks: a channel takes its own releases and every more stable
 * kind, so `beta` hears of betas and releases, and `alpha` of everything (ROADMAP.md, Updates).
 */
public enum class UpdateChannel(
    /** Modrinth's `version_type`. */
    public val modrinthType: String,
) {
    RELEASE("release"),
    BETA("beta"),
    ALPHA("alpha"),
    ;

    /** Whether someone on this channel hears of a release of kind [release]. */
    public fun includes(release: UpdateChannel): Boolean = release.ordinal <= ordinal

    public companion object {
        /** The channel of Modrinth's `version_type` [type], or `null` for one it doesn't have. */
        public fun ofModrinth(type: String): UpdateChannel? = entries.firstOrNull { it.modrinthType == type }

        /**
         * What kind of release [version] is, from its suffix, as the release workflow decides
         * it: none is a release, `-alpha…` an alpha, any other a beta.
         */
        public fun of(version: String): UpdateChannel {
            val suffix = Versions.prerelease(version) ?: return RELEASE
            return if (suffix.startsWith("alpha")) ALPHA else BETA
        }
    }
}

/** What the update checker does: whether it asks at all, and which [channel] it follows. */
public class UpdateSettings(
    /** Whether to ask Modrinth once a day. */
    public val check: Boolean,
    /** The least stable releases to hear of. */
    public val channel: UpdateChannel,
) {
    override fun equals(other: Any?): Boolean = other is UpdateSettings && other.check == check && other.channel == channel
    override fun hashCode(): Int = check.hashCode() * 31 + channel.hashCode()
    override fun toString(): String = "UpdateSettings(check=$check, channel=$channel)"

    public companion object {
        /** Asking, for releases only. */
        public val DEFAULT: UpdateSettings = UpdateSettings(true, UpdateChannel.RELEASE)
    }
}

/**
 * The `updates` section every plugin's config mounts: `check` and `channel`. Read with
 * [settings], at startup and on every reload, and handed to [UpdateChecker.apply].
 */
public class UpdatesSection(parent: ConfigGroup) : ConfigSection(
    parent, "updates",
    "Telling you when a newer version is out: in the console, and to players with",
    "<plugin>.admin.update when they join. Asks Modrinth once a day, off the server's threads,",
    "sending only the plugin's version, the platform and the Minecraft version.",
) {
    public val check: ConfigKey<Boolean> = boolean("check", true, "Whether to ask at all.")
    public val channel: ConfigKey<UpdateChannel> = enum(
        "channel", UpdateChannel.RELEASE, UpdateChannel.entries,
        "Which releases to hear of: release, beta (betas and releases) or alpha (everything).",
    )

    /** What [config] says. */
    public fun settings(config: Config): UpdateSettings = UpdateSettings(config[check], config[channel])
}

/** One published version of a plugin: its [version] number, its [channel] and its download [page]. */
public class Release(
    public val version: String,
    public val channel: UpdateChannel,
    public val page: String,
) {
    override fun equals(other: Any?): Boolean =
        other is Release && other.version == version && other.channel == channel && other.page == page
    override fun hashCode(): Int = (version.hashCode() * 31 + channel.hashCode()) * 31 + page.hashCode()
    override fun toString(): String = "Release($version, $channel, $page)"
}

/**
 * Where a plugin's releases are listed: Modrinth ([ModrinthReleases]). Asked off the server's
 * threads, once a day; it may block on the network. An error is a sentence in English, for
 * `/<plugin> debug`, never shown to players.
 */
public fun interface ReleaseSource {
    public fun releases(): Outcome<List<Release>, String>
}

/**
 * The newest of [releases] that [channel] includes and is newer than [current], or `null`.
 * Build metadata (`+fabric-1.21.11`) doesn't count.
 */
public fun newestFor(releases: List<Release>, current: String, channel: UpdateChannel): Release? {
    var newest: Release? = null
    for (release in releases) {
        if (!channel.includes(release.channel)) continue
        if (Versions.compare(release.version, current) <= 0) continue
        if (newest == null || Versions.compare(release.version, newest.version) > 0) newest = release
    }
    return newest
}

/** Compares versions like `2.0.0`, `2.1`, `2.0.0-beta.2` and `2.0.0+fabric-1.21.11`, as semver does. */
public object Versions {
    /** The pre-release part of [version] (`beta.2`), or `null` for a release. Build metadata isn't one. */
    public fun prerelease(version: String): String? {
        val core = version.substringBefore('+')
        val dash = core.indexOf('-')
        return if (dash < 0) null else core.substring(dash + 1)
    }

    /** Negative if [a] is older than [b], positive if newer, 0 if the same. */
    public fun compare(a: String, b: String): Int {
        val x = a.substringBefore('+')
        val y = b.substringBefore('+')
        val release = dotted(x.substringBefore('-'), y.substringBefore('-'))
        if (release != 0) return release
        val xPre = prerelease(x)
        val yPre = prerelease(y)
        // 2.0.0-beta.1 comes before 2.0.0.
        return when {
            xPre == null && yPre == null -> 0
            xPre == null -> 1
            yPre == null -> -1
            else -> dotted(xPre, yPre)
        }
    }

    private fun dotted(a: String, b: String): Int {
        val xs = a.split('.')
        val ys = b.split('.')
        for (i in 0 until maxOf(xs.size, ys.size)) {
            val x = xs.getOrElse(i) { "0" }
            val y = ys.getOrElse(i) { "0" }
            val xn = number(x)
            val yn = number(y)
            val result = when {
                xn != null && yn != null -> xn.compareTo(yn)
                // Numbers come before words, as in semver: 1.0.0-2 < 1.0.0-beta.
                xn != null -> -1
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (result != 0) return result
        }
        return 0
    }

    private fun number(part: String): Long? =
        if (part.isNotEmpty() && part.length < 18 && part.all { it in '0'..'9' }) part.toLong() else null
}
