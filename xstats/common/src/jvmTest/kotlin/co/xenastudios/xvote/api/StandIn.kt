package co.xenastudios.xvote.api

import co.xenastudios.core.api.event.EventBus

// xVote's API as xStats finds it, by name (xvote/api): its holder, its bus and the event xStats
// counts, with the same names and members. Gradle can't put two plugins' api projects on one
// classpath (each is co.xenastudios:api), so FamilyTest uses these stand-ins.

interface XVoteApi {
    val events: EventBus
}

object XVote {
    @Volatile
    private var running: XVoteApi? = null

    @JvmStatic
    fun api(): XVoteApi = running ?: throw IllegalStateException("xVote isn't enabled")

    @JvmStatic
    fun isEnabled(): Boolean = running != null

    @JvmStatic
    fun register(api: XVoteApi?) {
        running = api
    }
}
