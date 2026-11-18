package co.xenastudios.xvote.receiver;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Keys and tokens kept as NuVotifier keeps them, and taking over a NuVotifier folder. */
class NuVotifierImportTest {

    @TempDir
    Path plugins;

    @Test
    void keysAreWrittenAsNuVotifierWritesThem() throws Exception {
        Path rsa = plugins.resolve("xVote/rsa");
        KeyPair made = Keys.loadOrCreate(rsa);
        String publicText = Files.readString(rsa.resolve("public.key"));
        // One line of Base64, no header, no line breaks: what NuVotifier's RSAIO writes and reads.
        assertTrue(publicText.matches("[A-Za-z0-9+/=]+"), publicText);
        assertEquals(publicText, Keys.publicText(made));
        KeyPair read = Keys.load(rsa);
        assertArrayEquals(made.getPrivate().getEncoded(), read.getPrivate().getEncoded());
        // A second start reads the same pair rather than making a new one.
        assertArrayEquals(made.getPublic().getEncoded(), Keys.loadOrCreate(rsa).getPublic().getEncoded());
    }

    @Test
    void tokensFileMatchesNuVotifiersSection() throws Exception {
        Path folder = plugins.resolve("xVote");
        Tokens.Loaded first = Tokens.loadOrCreate(folder);
        assertTrue(first.created());
        String token = first.tokens().forService("default");
        assertTrue(token.matches("[0-9a-v]+"));
        String file = Files.readString(folder.resolve(Tokens.FILE));
        assertTrue(file.contains("tokens:\n  default: " + token), file);
        Tokens.Loaded second = Tokens.loadOrCreate(folder);
        assertFalse(second.created());
        assertEquals(token, second.tokens().forService("default"));
        // Exact name first, as NuVotifier looks it up; then any case.
        Tokens more = second.tokens().with("PlanetMinecraft.com", "abc");
        assertEquals("abc", more.forService("planetminecraft.com"));
        assertNull(more.forService("Other"));
    }

    @Test
    void takesOverNuVotifiersFolder() throws Exception {
        Path votifier = plugins.resolve("Votifier");
        Files.createDirectories(votifier.resolve("rsa"));
        for (String file : new String[] {"config.yml", "rsa/public.key", "rsa/private.key"}) {
            Files.copy(ProtocolTest.FIXTURES.resolve(file), votifier.resolve(file));
        }
        assertEquals(votifier, NuVotifierImport.find(plugins));
        NuVotifierImport.Found found = NuVotifierImport.read(votifier);
        assertEquals(8193, found.port());
        assertTrue(found.v1());
        assertEquals("8m9f5ljm8okk2gcq2ncv8g1ab5", found.tokens().forService("MinecraftServers.org"));
        assertEquals(ProtocolTest.TOKEN, found.tokens().forService("default"));

        Path xvote = plugins.resolve("xVote");
        NuVotifierImport.write(found, xvote);
        // The same key files, byte for byte as keys, so lists keep the public key they have.
        assertArrayEquals(Keys.load(votifier.resolve("rsa")).getPublic().getEncoded(), Keys.load(xvote.resolve("rsa")).getPublic().getEncoded());
        Tokens tokens = Tokens.loadOrCreate(xvote).tokens();
        assertEquals("8m9f5ljm8okk2gcq2ncv8g1ab5", tokens.forService("MinecraftServers.org"));
        assertEquals(ProtocolTest.TOKEN, tokens.forService("default"));
    }

    @Test
    void noNuVotifierNoImport() {
        assertNull(NuVotifierImport.find(plugins));
    }
}
