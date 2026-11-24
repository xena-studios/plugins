package co.xenastudios.core.api

/**
 * What every plugin's API shares: player references by UUID, locations and events, the same
 * on every platform (ROADMAP.md, Developer APIs).
 */
public object CoreApi {
    /**
     * The version of what's in this module, raised when something is added. An API that
     * needs something newer can check it.
     */
    public const val LEVEL: Int = 1
}
