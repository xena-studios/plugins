package co.xenastudios.xtwin.neoforge

import co.xenastudios.core.jvm.family.JvmFamilyBoard
import co.xenastudios.core.neoforge.NeoForgePlatform
import co.xenastudios.core.twin.XTwin
import net.neoforged.bus.api.EventPriority
import net.neoforged.fml.ModList
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.server.ServerStoppingEvent

/**
 * xTwin as a NeoForge mod, for the family test beside xSample: its own core, relocated to
 * `co.xenastudios.xtwin.core`, on the board in the system properties and in `config/xena/`.
 */
@Mod("xtwin")
public class XTwinMod {
    init {
        val platform = NeoForgePlatform("xtwin", "xTwin")
        val family = platform.family
        val twin = XTwin(platform, family.family, family.players, platform.commands, about = platform::about) {
            // The system properties hold one board, the one this mod's core uses, and xSample is there.
            val root = (family.family.board as JvmFamilyBoard).root
            listOfNotNull(
                "the system properties' ${JvmFamilyBoard.NAME} isn't this mod's board".takeIf { System.getProperties()[JvmFamilyBoard.NAME] !== root },
                "xSample isn't loaded".takeIf { !ModList.get().isLoaded("xsample") },
            )
        }
        platform.permissions.sync(twin.admin.permissions.permissions)
        twin.enable()
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ServerStoppingEvent::class.java) { _ -> twin.disable() }
        platform.log.info("Enabled on ${platform.name}")
    }
}
