package co.xenastudios.zchat

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.isFatal
import co.xenastudios.core.common.locked
import co.xenastudios.core.common.platform.Log
import co.xenastudios.zchat.api.ChatPlaceholder
import co.xenastudios.zchat.api.HiddenPlayers
import co.xenastudios.zchat.api.MessageKind
import co.xenastudios.zchat.api.MuteProvider
import kotlin.concurrent.Volatile

/**
 * What other plugins plug into zChat through its API: mute providers (xBans), hidden players
 * (xVanish) and placeholders (xMoney). Asked on every message, on many threads at once, so each
 * list is an array replaced whole on every change: asking reads it once and takes no lock, and
 * with none registered costs one read.
 */
public class Hooks(private val log: Log) {
    private class Owned<T : Any>(val owner: String, val hook: T)

    @Volatile
    private var mutes: Array<Owned<MuteProvider>> = emptyArray()

    @Volatile
    private var hidden: Array<Owned<HiddenPlayers>> = emptyArray()

    @Volatile
    private var tags: Map<String, Owned<ChatPlaceholder>> = emptyMap()

    private val lock = Any()

    public fun addMute(owner: String, provider: MuteProvider) {
        synchronizedOn { if (mutes.none { it.hook === provider }) mutes += Owned(owner, provider) }
    }

    public fun removeMute(provider: MuteProvider) {
        synchronizedOn { mutes = mutes.filter { it.hook !== provider }.toTypedArray() }
    }

    public fun addHidden(owner: String, hook: HiddenPlayers) {
        synchronizedOn { if (hidden.none { it.hook === hook }) hidden += Owned(owner, hook) }
    }

    public fun removeHidden(hook: HiddenPlayers) {
        synchronizedOn { hidden = hidden.filter { it.hook !== hook }.toTypedArray() }
    }

    public fun addPlaceholder(owner: String, name: String, placeholder: ChatPlaceholder) {
        synchronizedOn { tags = tags + (name to Owned(owner, placeholder)) }
    }

    public fun removePlaceholder(name: String) {
        synchronizedOn { tags = tags - name }
    }

    /** Whether any mute provider is registered: one read, for the hot path. */
    public val hasMutes: Boolean get() = mutes.isNotEmpty()

    /** What the first mute provider with a reason says, or `null` if [player] may send it. */
    public fun muteReason(player: PlayerRef, kind: MessageKind): String? {
        for (entry in mutes) {
            try {
                entry.hook.muteReason(player, kind)?.let { return it }
            } catch (e: Throwable) {
                if (isFatal(e)) throw e
                // A broken provider mustn't break chat: it's dropped, and said once.
                removeMute(entry.hook)
                log.error("${entry.owner}'s mute provider failed, so zChat stopped asking it", e)
            }
        }
        return null
    }

    /** Whether a vanish plugin hides [target] from [viewer]. */
    public fun isHidden(viewer: PlayerRef, target: PlayerRef): Boolean {
        for (entry in hidden) {
            try {
                if (entry.hook.isHidden(viewer, target)) return true
            } catch (e: Throwable) {
                if (isFatal(e)) throw e
                removeHidden(entry.hook)
                log.error("${entry.owner}'s hidden-players hook failed, so zChat stopped asking it", e)
            }
        }
        return false
    }

    /** The placeholders other plugins gave formats, by tag name. */
    public val placeholderNames: Set<String> get() = tags.keys

    /** Each placeholder's value for [player], as markup, by tag name: once per message. */
    public fun placeholders(player: PlayerRef): Map<String, String> {
        val all = tags
        if (all.isEmpty()) return emptyMap()
        val out = HashMap<String, String>(all.size * 2)
        for ((name, entry) in all) {
            try {
                entry.hook.value(player)?.let { out[name] = it }
            } catch (e: Throwable) {
                if (isFatal(e)) throw e
                removePlaceholder(name)
                log.error("${entry.owner}'s <$name> placeholder failed, so zChat stopped asking it", e)
            }
        }
        return out
    }

    /** Forgets every hook, as zChat stops. */
    public fun clear() {
        synchronizedOn {
            mutes = emptyArray()
            hidden = emptyArray()
            tags = emptyMap()
        }
    }

    private fun synchronizedOn(block: () -> Unit) {
        locked(lock, block)
    }
}
