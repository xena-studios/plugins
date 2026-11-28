package co.xenastudios.zchat.neoforge

import co.xenastudios.core.api.PlayerRef
import co.xenastudios.core.common.config.Sound
import co.xenastudios.core.common.text.Component
import co.xenastudios.core.neoforge.NeoForgePlatform
import co.xenastudios.core.neoforge.MinecraftRenderer
import co.xenastudios.core.neoforge.player
import co.xenastudios.zchat.ChatServer
import net.minecraft.core.Holder
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
//? if identifier {
import net.minecraft.resources.Identifier
//?} else
//import net.minecraft.resources.ResourceLocation as Identifier

/**
 * What zChat does to players on a NeoForge server, with Minecraft's own classes, no mixins: its
 * Fabric adapter's, which is all Minecraft's. Chat and commands are on the server thread,
 * NeoForge's only one; a message from elsewhere hops there. NeoForge has no vanish of its own, so
 * who may see whom is zChat's API's alone (a vanish mod registers through it), and no
 * placeholder mod is read yet, so `%placeholders%` show nothing.
 */
internal class NeoForgeChatServer(private val platform: NeoForgePlatform) : ChatServer {
    private fun player(ref: PlayerRef): ServerPlayer? = platform.server?.player(ref)

    override fun name(player: PlayerRef): String? = player(player)?.scoreboardName

    override fun displayName(player: PlayerRef): Component = Component.text(player(player)?.displayName?.string ?: "")

    override fun has(player: PlayerRef, node: String): Boolean = player(player)?.let { platform.permissions.has(it, node) } == true

    override fun canSee(viewer: PlayerRef, target: PlayerRef): Boolean = true

    override fun send(player: PlayerRef, message: Component) {
        val text = MinecraftRenderer.draw(message)
        val server = platform.server ?: return
        if (server.isSameThread) player(player)?.sendSystemMessage(text) else server.execute { player(player)?.sendSystemMessage(text) }
    }

    override fun console(message: Component) {
        platform.log.info(message.plain())
    }

    override fun playSound(player: PlayerRef, sound: Sound) {
        if (sound.isNone) return
        val p = player(player) ?: return
        val id = Identifier.tryParse(sound.name) ?: return
        val event = SoundEvent.createVariableRangeEvent(id)
        p.connection.send(ClientboundSoundPacket(Holder.direct(event), SoundSource.PLAYERS, p.x, p.y, p.z, sound.volume, sound.pitch, p.random.nextLong()))
    }
}
