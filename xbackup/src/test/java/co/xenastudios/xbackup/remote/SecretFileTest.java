package co.xenastudios.xbackup.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The secrets files, read without a server, the same way the server's YAML reads them. */
class SecretFileTest {

    @TempDir
    Path dir;

    @Test
    void whatItWritesReadsBackTheSameHereAndOnTheServer() throws Exception {
        Map<String, Map<String, String>> values = new LinkedHashMap<>();
        Map<String, String> offsite = new LinkedHashMap<>();
        offsite.put("password", "p\"a:s s#w\\o'rd é\t\u0001 <&>\u007f\u2028 end");
        offsite.put("client-secret", "");
        values.put("offsite", offsite);
        values.put("odd name", Map.of("password", "- [x] {y}: &z *w !v |u >t"));
        Path file = dir.resolve("secrets.yml");
        SecretFile.write(file, "Header line\n\nmore", values);
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals(values, SecretFile.read(file));
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(Files.readString(file));
        assertEquals(offsite.get("password"), yaml.getString("offsite.password"));
        assertEquals("- [x] {y}: &z *w !v |u >t", yaml.getString("odd name.password"));
    }

    @Test
    void whatPeopleTypeIsRead() throws Exception {
        Path file = dir.resolve("secrets.yml");
        Files.writeString(file, """
            # Mine.
            offsite:
              password: hunter2   # the usual
              username: 'it''s me'
            gdrive:
                client-secret: "a\\"b\\u00e9"
            empty: {}
            blank:
              password:
            """);
        Map<String, Map<String, String>> read = SecretFile.read(file);
        assertEquals("hunter2", read.get("offsite").get("password"));
        assertEquals("it's me", read.get("offsite").get("username"));
        assertEquals("a\"bé", read.get("gdrive").get("client-secret"));
        assertTrue(read.get("empty").isEmpty());
        assertEquals("", read.get("blank").get("password"));
        assertTrue(SecretFile.read(dir.resolve("none.yml")).isEmpty());
    }

    @Test
    void whatItCantReadIsRefusedNotMisread() throws Exception {
        for (String text : new String[] {"offsite:\n  password: |\n    hunter2\n", "offsite:\n  password: \"open\n",
            "  password: orphan\n", "offsite: inline\n", "offsite:\n  password: 'a' b\n"}) {
            Path file = dir.resolve("bad.yml");
            Files.writeString(file, text);
            IOException e = assertThrows(IOException.class, () -> SecretFile.read(file), text);
            assertTrue(e.getMessage().contains("line "), e.getMessage());
            assertFalse(e.getMessage().contains("hunter2"), "never shows the value");
        }
    }

    @Test
    void theCommandLineReadsPasswordsFromSecretsNotItsOwnCopy() throws Exception {
        Files.writeString(dir.resolve(RestoreSettings.NAME), "repository=/srv/repo\nremote.offsite.type=sftp\n"
            + "remote.offsite.host=example.com\nremote.offsite.username=backup\n");
        Files.writeString(dir.resolve(SecretFile.SECRETS), "offsite:\n  password: \"hunter2\"\n");
        RestoreSettings settings = RestoreSettings.read(dir);
        assertEquals("hunter2", settings.remotes().get("offsite").password());
        assertEquals(null, settings.secretsProblem());
        Files.writeString(dir.resolve(SecretFile.SECRETS), "offsite:\n  password: >\n    folded\n");
        RestoreSettings broken = RestoreSettings.read(dir);
        assertEquals("", broken.remotes().get("offsite").password());
        assertTrue(broken.secretsProblem().contains("line 2"), broken.secretsProblem());
    }
}
