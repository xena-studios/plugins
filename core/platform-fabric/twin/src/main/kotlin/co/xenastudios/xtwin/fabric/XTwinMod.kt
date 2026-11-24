package co.xenastudios.xtwin.fabric

import co.xenastudios.core.fabric.FabricPlatform
import co.xenastudios.core.jvm.family.JvmFamilyBoard
import co.xenastudios.core.twin.XTwin
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.loader.api.FabricLoader

/**
 * xTwin as a Fabric mod, for the family test beside xSample: its own core, relocated to
 * `co.xenastudios.xtwin.core`, on the board in Fabric's object share and in `config/xena/`.
 */
public object XTwinMod : ModInitializer {
    override fun onInitialize() {
        val platform = FabricPlatform("xtwin", "xTwin")
        val family = platform.family
        val twin = XTwin(platform, family.family, family.players, platform.commands, about = platform::about) {
            // The object share holds one board, the one this mod's core uses, and xSample is there.
            val root = (family.family.board as JvmFamilyBoard).root
            listOfNotNull(
                "the object share's ${JvmFamilyBoard.NAME} isn't this mod's board".takeIf { FabricLoader.getInstance().objectShare.get(JvmFamilyBoard.NAME) !== root },
                "xSample isn't loaded".takeIf { !FabricLoader.getInstance().isModLoaded("xsample") },
            )
        }
        platform.permissions.sync(twin.admin.permissions.permissions)
        twin.enable()
        ServerLifecycleEvents.SERVER_STOPPING.register { _ -> twin.disable() }
        platform.log.info("Enabled on ${platform.name}")
    }
}
