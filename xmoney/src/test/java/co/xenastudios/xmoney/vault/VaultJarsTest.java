package co.xenastudios.xmoney.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Finding a Vault or VaultUnlocked jar left in plugins/, whatever it's called. */
class VaultJarsTest {

    @TempDir
    Path plugins;

    private void jar(String file, String descriptor, String yaml) throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(plugins.resolve(file)))) {
            zip.putNextEntry(new ZipEntry(descriptor));
            zip.write(yaml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    @Test
    void findsVaultByItsName() throws Exception {
        jar("VaultUnlocked-2.20.1.jar", "plugin.yml", "name: Vault\nversion: 2.20.1\nmain: net.milkbowl.vault.Vault\n");
        jar("aaa.jar", "plugin.yml", "name: 'Vault'\n");
        jar("xMoney-1.0.0.jar", "paper-plugin.yml", "api-version: 1.21.11\nname: xMoney\nprovides:\n- Vault\n");
        jar("VaultChatFormatter.jar", "plugin.yml", "name: VaultChatFormatter\n");
        Files.writeString(plugins.resolve("broken.jar"), "not a zip");
        assertEquals(List.of("VaultUnlocked-2.20.1.jar", "aaa.jar"), VaultServices.vaultJars(plugins));
    }
}
