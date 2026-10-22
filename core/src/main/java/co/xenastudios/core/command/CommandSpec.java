package co.xenastudios.core.command;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.permissions.PermissionDefault;

/**
 * One command: its name, aliases, permission, and what it does.
 *
 * @param permission blank means anyone may use it
 * @param permissionDefault who has the permission when no permissions plugin says otherwise
 * @param usage how to type it, such as {@code /speed <1-10> [player]}, for the usage error
 */
public record CommandSpec(
    String name,
    List<String> aliases,
    String permission,
    PermissionDefault permissionDefault,
    String description,
    String usage,
    Executor executor,
    Completer completer) {

    public CommandSpec {
        String lowerName = name.toLowerCase(Locale.ROOT);
        name = lowerName;
        aliases = aliases.stream()
            .map(alias -> alias.toLowerCase(Locale.ROOT).strip())
            .filter(alias -> !alias.isEmpty() && !alias.equals(lowerName))
            .distinct()
            .toList();
        Objects.requireNonNull(executor);
        Objects.requireNonNull(completer);
    }

    public static Builder named(String name) {
        return new Builder(name);
    }

    public static final class Builder {

        private final String name;
        private List<String> aliases = List.of();
        private String permission = "";
        private PermissionDefault permissionDefault = PermissionDefault.OP;
        private String description = "";
        private String usage;
        private Completer completer = Completer.NONE;

        private Builder(String name) {
            this.name = name;
            this.usage = "/" + name;
        }

        public Builder aliases(List<String> aliases) {
            this.aliases = aliases;
            return this;
        }

        public Builder permission(String permission) {
            this.permission = permission;
            return this;
        }

        /** Who has the permission by default. {@code OP} unless set. */
        public Builder permissionDefault(PermissionDefault permissionDefault) {
            this.permissionDefault = permissionDefault;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder usage(String usage) {
            this.usage = usage;
            return this;
        }

        public Builder completes(Completer completer) {
            this.completer = completer;
            return this;
        }

        public CommandSpec executes(Executor executor) {
            return new CommandSpec(name, aliases, permission, permissionDefault, description, usage, executor, completer);
        }
    }
}
