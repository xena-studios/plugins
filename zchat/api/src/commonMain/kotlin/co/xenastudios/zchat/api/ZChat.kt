package co.xenastudios.zchat.api

import kotlin.concurrent.Volatile
import kotlin.jvm.JvmStatic

/**
 * Where zChat's API is found, on every platform:
 *
 * ```java
 * ZChatApi api = ZChat.api();
 * ```
 *
 * @since 4.0.0
 */
public object ZChat {
    @Volatile
    private var running: ZChatApi? = null

    /**
     * zChat's API.
     *
     * @throws IllegalStateException if zChat isn't installed and enabled: add it to your
     *   plugin's `depend` (or `softdepend`, and check [isEnabled] first)
     */
    @JvmStatic
    public fun api(): ZChatApi =
        running ?: throw IllegalStateException("zChat isn't enabled. Add it to depend (or softdepend and check ZChat.isEnabled() first).")

    /** Whether zChat is enabled, so [api] answers. */
    @JvmStatic
    public fun isEnabled(): Boolean = running != null

    /** Made available by zChat as it's enabled, and taken away as it's disabled: not for other plugins. */
    @JvmStatic
    public fun register(api: ZChatApi?) {
        running = api
    }
}
