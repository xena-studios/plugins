import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property

/**
 * What the `xena.r8` convention shrinks. Every property has a default that fits a plugin jar
 * built with Shadow (or a plain `jar`), so most projects set nothing.
 */
abstract class XenaR8Extension {
    /** The jar to shrink: `shadowJar`'s if Shadow is applied, else `jar`'s. */
    abstract val inputJar: ConfigurableFileCollection

    /**
     * What's there at runtime but not in the jar (the server's API, the fetched stdlib): the
     * main compile class path unless set. Anything shaded into the jar is left out of it.
     */
    abstract val libraries: ConfigurableFileCollection

    /**
     * The package R8 moves renamed classes to, of the plugin's own: the plugin's id (`xbans`),
     * the first name of the project's path unless set. Short, since every class name and
     * descriptor in the jar carries it (`co.xenastudios.xutilities.r8` cost xUtilities 33 KB), and
     * unique on a server: Bukkit and Fabric load one plugin of a name, and the id is ours.
     */
    abstract val packageName: Property<String>

    /** The project's own keep rules, after the shared ones: the `.pro` files in its `r8` folder unless set. */
    abstract val rules: ConfigurableFileCollection

    /**
     * The classes the plugin loads as it starts, in their own names, for the shim to load ahead
     * (R8Shrink): the project's `startup-classes.txt` if it has one. Record it with
     * `scripts/budgets/check.sh --record-startup`.
     */
    abstract val startupClasses: ConfigurableFileCollection
}
