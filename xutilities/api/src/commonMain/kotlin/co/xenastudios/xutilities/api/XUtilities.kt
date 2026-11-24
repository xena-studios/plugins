package co.xenastudios.xutilities.api

import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic

/**
 * Where xUtilities' API is found, on every platform:
 *
 * ```java
 * XUtilitiesApi api = XUtilities.api();
 * ```
 *
 * @since 3.0.0
 */
public object XUtilities {
    @Volatile
    private var running: XUtilitiesApi? = null

    /**
     * xUtilities' API.
     *
     * @throws IllegalStateException if xUtilities isn't installed and enabled: add it to your
     *   plugin's `depend` (or `softdepend`, and check [isEnabled] first)
     */
    @JvmStatic
    public fun api(): XUtilitiesApi =
        running ?: throw IllegalStateException("xUtilities isn't enabled. Add it to depend (or softdepend and check XUtilities.isEnabled() first).")

    /** Whether xUtilities is enabled, so [api] answers. */
    @JvmStatic
    public fun isEnabled(): Boolean = running != null

    /** Made available by xUtilities as it's enabled, and taken away as it's disabled: not for other plugins. */
    @JvmStatic
    public fun register(api: XUtilitiesApi?) {
        running = api
    }
}
