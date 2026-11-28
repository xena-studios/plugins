package net.minecraft.xenatest

import com.mojang.brigadier.Command

/** Stands in for one of Minecraft's own command executors: a class in Minecraft's package. */
object VanillaCommand : Command<Any> {
    override fun run(context: com.mojang.brigadier.context.CommandContext<Any>): Int = 1
}
