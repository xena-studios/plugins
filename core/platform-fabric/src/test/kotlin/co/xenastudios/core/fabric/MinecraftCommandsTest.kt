package co.xenastudios.core.fabric

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.builder.LiteralArgumentBuilder.literal
import net.minecraft.xenatest.VanillaCommand
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinecraftCommandsTest {
    @Test
    fun aRedirectIsMinecraftsWhenItsTargetIs() {
        val dispatcher = CommandDispatcher<Any>()
        val msg = dispatcher.register(literal<Any>("msg").executes(VanillaCommand))
        // Minecraft's /tell and /w: a literal that only redirects to /msg.
        val tell = dispatcher.register(literal<Any>("tell").redirect(msg))
        val mods = dispatcher.register(literal<Any>("modmsg").executes { 1 })
        val modAlias = dispatcher.register(literal<Any>("mm").redirect(mods))
        assertTrue(MinecraftCommands.owns(msg))
        assertTrue(MinecraftCommands.owns(tell))
        assertFalse(MinecraftCommands.owns(mods))
        assertFalse(MinecraftCommands.owns(modAlias))
    }
}
