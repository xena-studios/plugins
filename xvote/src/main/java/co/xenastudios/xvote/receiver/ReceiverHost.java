package co.xenastudios.xvote.receiver;

import co.xenastudios.xvote.vote.IncomingVote;
import java.io.IOException;
import java.net.BindException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps the receiver matching the config: started, stopped, or restarted when its
 * address or limits change, and given the current keys and tokens on every reload. Keys
 * and tokens are files, so all of it runs off the server's threads, one change at a time.
 */
public final class ReceiverHost {

    private final Path folder;
    private final Consumer<IncomingVote> sink;
    private final Logger logger;
    // Guarded by this.
    private Receiver receiver;
    private Receiver.Settings failedSettings;
    private Receiver.Credentials credentials;
    private boolean closed;

    public ReceiverHost(Path folder, Consumer<IncomingVote> sink, Logger logger) {
        this.folder = folder;
        this.sink = sink;
        this.logger = logger;
    }

    /**
     * Makes the receiver match the settings: null stops it. Blocks while it reads keys and
     * binds the port, so call it off the server's threads.
     */
    public synchronized void apply(Receiver.Settings settings) {
        if (closed) {
            // The plugin was disabled while this waited its turn: never bind after that.
            return;
        }
        Receiver.Credentials loaded = loadCredentials();
        if (loaded != null) {
            credentials = loaded;
        }
        if (settings == null || credentials == null) {
            stop();
            return;
        }
        if (receiver != null && receiver.settings().equals(settings)) {
            receiver.credentials(credentials);
            return;
        }
        stop();
        Receiver next = new Receiver(settings, credentials, sink, logger);
        try {
            next.start();
            receiver = next;
            failedSettings = null;
            logger.info("Listening for votes on " + (settings.host().isBlank() ? "0.0.0.0" : settings.host()) + ":"
                + next.port() + " (Votifier " + (settings.v1() ? "v1 and v2" : "v2 only") + ").");
        } catch (IOException e) {
            next.close();
            // Logged once per setting, not on every reload that tries the same port.
            if (!settings.equals(failedSettings)) {
                failedSettings = settings;
                String why = e instanceof BindException ? "something else is using it (NuVotifier, or another server?)" : e.getMessage();
                logger.severe("Couldn't listen for votes on port " + settings.port() + ": " + why
                    + ". Votes from lists can't arrive until receiver.port is free or changed; /xvote reload tries again.");
            }
        }
    }

    /** Stops listening for good, as the plugin is disabled. A change still waiting does nothing. */
    public synchronized void close() {
        closed = true;
        stop();
    }

    /** Stops listening. */
    public synchronized void stop() {
        if (receiver != null) {
            receiver.close();
            receiver = null;
        }
    }

    /** The running receiver, or null. */
    public synchronized Receiver receiver() {
        return receiver;
    }

    /** The keys and tokens last read, or null if they couldn't be. */
    public synchronized Receiver.Credentials credentials() {
        return credentials;
    }

    /** Saves new tokens and uses them from now on. Off the server's threads. */
    public synchronized void saveTokens(Tokens tokens) throws IOException {
        tokens.save(folder);
        credentials = new Receiver.Credentials(credentials == null ? null : credentials.keys(), tokens);
        if (receiver != null) {
            receiver.credentials(credentials);
        }
    }

    private Receiver.Credentials loadCredentials() {
        KeyPair keys;
        try {
            boolean made = !Keys.exist(folder.resolve("rsa"));
            keys = Keys.loadOrCreate(folder.resolve("rsa"));
            if (made) {
                logger.info("Made a new RSA key pair for Votifier v1 in rsa/. Lists that ask for a public key get it with /xvote key.");
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Couldn't read the Votifier keys: " + e.getMessage());
            return null;
        }
        try {
            Tokens.Loaded loaded = Tokens.loadOrCreate(folder);
            if (loaded.created()) {
                logger.info("Made a Votifier token in tokens.yml. Lists that ask for a token get it with /xvote token.");
            }
            return new Receiver.Credentials(keys, loaded.tokens());
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Couldn't read " + Tokens.FILE + ": " + e.getMessage());
            return null;
        }
    }
}
