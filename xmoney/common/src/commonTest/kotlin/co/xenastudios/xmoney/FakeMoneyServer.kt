package co.xenastudios.xmoney

import co.xenastudios.core.api.Pending
import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.pendingOf
import co.xenastudios.core.common.i18n.LanguageSource
import co.xenastudios.core.common.text.Component

/** A server for xMoney's tests: who's online, who has played here, and what each was sent. */
class FakeMoneyServer : MoneyServer, LanguageSource {
    val online = LinkedHashMap<PlayerRef, String>()
    val played = LinkedHashMap<String, PlayerRef>()
    val sent = HashMap<PlayerRef, MutableList<String>>()
    val locales = HashMap<PlayerRef, String>()

    fun join(player: PlayerRef, name: String) {
        online[player] = name
        played[name.lowercase()] = player
    }

    override fun send(player: PlayerRef, message: Component) {
        sent.getOrPut(player) { ArrayList() } += message.plain()
    }

    override fun isOnline(player: PlayerRef): Boolean = player in online

    override fun onlineNames(): List<String> = online.values.toList()

    override fun known(name: String): Pending<KnownPlayer?> =
        pendingOf(played[name.lowercase()]?.let { id -> KnownPlayer(id, played.entries.first { it.value == id }.key.let { online[id] ?: it }) })

    override fun cachedName(player: PlayerRef): String? = online[player]

    override fun clientLocale(player: PlayerRef): String? = locales[player]
}
