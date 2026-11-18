package co.xenastudios.xvote.receiver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The RSA key pair for Votifier v1, kept as NuVotifier keeps it: {@code rsa/public.key}
 * (X.509) and {@code rsa/private.key} (PKCS#8), each one line of Base64. So NuVotifier's
 * folder can be copied in as it is, and lists keep the public key they already have.
 */
public final class Keys {

    static final String PUBLIC = "public.key";
    static final String PRIVATE = "private.key";
    static final int BITS = 2048;

    private Keys() {}

    /** Whether the folder has both key files. */
    public static boolean exist(Path folder) {
        return Files.isRegularFile(folder.resolve(PUBLIC)) && Files.isRegularFile(folder.resolve(PRIVATE));
    }

    /**
     * Reads the key pair from the folder, or makes and saves one if it has none.
     *
     * @throws IOException if the files can't be read or written, or don't hold a key pair
     */
    public static KeyPair loadOrCreate(Path folder) throws IOException {
        if (exist(folder)) {
            return load(folder);
        }
        KeyPair pair = generate();
        save(folder, pair);
        return pair;
    }

    /** Reads the key pair from the folder. */
    public static KeyPair load(Path folder) throws IOException {
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            KeyPair pair = new KeyPair(
                factory.generatePublic(new X509EncodedKeySpec(read(folder.resolve(PUBLIC)))),
                factory.generatePrivate(new PKCS8EncodedKeySpec(read(folder.resolve(PRIVATE)))));
            if (((RSAPublicKey) pair.getPublic()).getModulus().bitLength() > BITS) {
                throw new IOException("The key in " + folder + " is longer than " + BITS + " bits; Votifier v1 needs a "
                    + BITS + "-bit key. Delete the folder to make a new one, and give lists the new public key.");
            }
            return pair;
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new IOException("The files in " + folder + " aren't an RSA key pair: " + e.getMessage(), e);
        }
    }

    /** A new 2048-bit pair, as NuVotifier makes them. */
    public static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(new RSAKeyGenParameterSpec(BITS, RSAKeyGenParameterSpec.F4));
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("This Java can't make RSA keys", e);
        }
    }

    /** Writes the pair, each file one line of Base64. The private key is readable by the server's user only, where the file system allows. */
    public static void save(Path folder, KeyPair pair) throws IOException {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(PUBLIC), encode(pair.getPublic().getEncoded()), StandardCharsets.US_ASCII);
        // Base64 is ASCII, so the UTF-8 Secrets writes is the same bytes.
        Secrets.write(folder.resolve(PRIVATE), encode(pair.getPrivate().getEncoded()));
    }

    /** The public key as lists ask for it: Base64 of its X.509 form, on one line. */
    public static String publicText(KeyPair pair) {
        return encode(pair.getPublic().getEncoded());
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    // As NuVotifier reads them: ASCII, trimmed, Base64. Some editors add a line break.
    private static byte[] read(Path file) throws IOException {
        String text = Files.readString(file, StandardCharsets.US_ASCII).trim().replaceAll("\\s+", "");
        return Base64.getDecoder().decode(text);
    }
}
