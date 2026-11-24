package co.xenastudios.xsample.bukkit

import co.xenastudios.core.bukkit.BukkitPlatform
import co.xenastudios.core.common.scheduler.Where
import co.xenastudios.core.common.scheduler.now
import co.xenastudios.core.loader.PluginEntry
import co.xenastudios.core.sample.SamplePermissions
import co.xenastudios.core.sample.XSample
import org.bukkit.plugin.java.JavaPlugin

/**
 * xSample on Bukkit: the shared plugin on [BukkitPlatform], loaded by the core's Java shim once
 * the stdlib is visible. Everything it does is the shared code's; this only wires it to the
 * server, as each plugin's `bukkit/` adapter will, then runs [PlatformCheck], which the boot
 * tests read.
 */
public class XSampleBukkit(private val plugin: JavaPlugin) : PluginEntry {

    private var platform: BukkitPlatform? = null
    private var sample: XSample? = null

    override fun enable() {
        val started = System.nanoTime()
        // A development build, so code on the wrong thread throws here as it would break on Folia.
        val platform = BukkitPlatform.create(plugin, development = true)
        val sample = XSample(platform, platform.players, registrar = platform.commands)
        this.platform = platform
        this.sample = sample
        platform.permissions.sync(SamplePermissions.permissions + sample.admin.permissions.permissions)
        // Modules turn on on the global thread: this one on Bukkit, the next global tick on Folia.
        platform.scheduler.now(Where.Global) { sample.enable() }
        val millis = (System.nanoTime() - started) / 1_000_000
        platform.log.info("xSample on ${platform.name}: ${platform.threading} threading, text as ${platform.text}, enabled in $millis ms")
        PlatformCheck(plugin, platform).start()
    }

    override fun disable() {
        val platform = platform ?: return
        sample?.modules?.clear()
        platform.close()
        this.platform = null
        sample = null
        platform.log.info("xSample stopped")
    }
}
