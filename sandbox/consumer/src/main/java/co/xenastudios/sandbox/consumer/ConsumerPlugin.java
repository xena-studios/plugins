package co.xenastudios.sandbox.consumer;

import co.xenastudios.sandbox.api.SandboxApi;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Never released. An ordinary plugin.yml plugin using the sandbox's API, and xRewind's when it's installed. */
public final class ConsumerPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("API CONSUMER " + SandboxApi.get().ping());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equals("xrewind") && getServer().getPluginManager().isPluginEnabled("xRewind")) {
            // Its own class, loaded only once xRewind is known to be there.
            XRewindCheck.run(this);
            return true;
        }
        return false;
    }
}
