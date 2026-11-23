import org.gradle.api.provider.Property

/** The settings a plugin module gives the `xena.plugin` convention. */
abstract class XenaPluginExtension {
    /** The most the released jar may weigh, in KiB. `check` fails above it. */
    abstract val jarBudgetKb: Property<Int>

    /** Whether the plugin runs on Folia. Sets the descriptor and the Modrinth loaders. */
    abstract val folia: Property<Boolean>

    /** The plugin's Modrinth project slug. Defaults to the module name. */
    abstract val modrinthId: Property<String>

    /**
     * The plugin's Hangar project slug, such as `xPlots`. Unset until the project exists on
     * hangar.papermc.io; until then releases and page syncs skip Hangar.
     */
    abstract val hangarId: Property<String>

    /**
     * The plugin's voxel.shop resource id: the number at the end of its link. Unset until the
     * resource exists; until then releases skip voxel.shop.
     */
    abstract val voxelId: Property<Int>
}
