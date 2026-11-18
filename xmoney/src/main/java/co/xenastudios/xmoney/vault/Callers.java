package co.xenastudios.xmoney.vault;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Which plugin called, for the ledger: Vault 1's calls don't say, so it's read off the
 * stack, the first frame from a plugin other than xMoney (whose class loader also holds
 * Vault's API classes). Each class is looked up once. A few microseconds per payment;
 * balances and checks don't ask.
 */
final class Callers {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static final ClassValue<String> PLUGIN = new ClassValue<>() {
        @Override
        protected String computeValue(Class<?> type) {
            try {
                Plugin plugin = JavaPlugin.getProvidingPlugin(type);
                return plugin.getName();
            } catch (IllegalArgumentException | IllegalStateException e) {
                return "";
            }
        }
    };

    private Callers() {}

    /** The calling plugin's name, or {@code fallback} when no plugin is on the stack (a console command, say). */
    static String plugin(ClassLoader ours, String fallback) {
        return WALKER.walk(frames -> frames
            .map(StackWalker.StackFrame::getDeclaringClass)
            .filter(type -> type.getClassLoader() != ours)
            .map(PLUGIN::get)
            .filter(name -> !name.isEmpty())
            .findFirst()
            .orElse(fallback));
    }
}
