package co.xenastudios.xvote.api.event

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.api.event.Event
import co.xenastudios.core.api.event.EventType

/** xVote's VoteRewardedEvent, as its API has it (xvote/api). */
class VoteRewardedEvent(val player: PlayerRef, val site: String, val rewards: List<String>) : Event {
    companion object {
        @JvmField
        val TYPE: EventType<VoteRewardedEvent> = EventType.of("xvote:rewarded")
    }
}
