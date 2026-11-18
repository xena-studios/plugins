package co.xenastudios.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CommandSpecTest {

    record Settings(boolean enabled, List<String> aliases, String permission) implements Toggle {}

    @Test
    void namedFromSettingsTakesItsAliasesAndPermission() {
        CommandSpec spec = CommandSpec.named("Fly", new Settings(true, List.of("Flight", "fly", " "), "x.command.fly"))
            .executes(invocation -> {});
        assertEquals("fly", spec.name());
        // Lower-cased, blanks and the command's own name dropped, as for any command.
        assertEquals(List.of("flight"), spec.aliases());
        assertEquals("x.command.fly", spec.permission());
    }
}
