package co.xenastudios.xbackup.remote;

import co.xenastudios.xbackup.config.XBConfig;
import co.xenastudios.xbackup.store.Backend;
import java.io.IOException;

/** Google Drive, OneDrive and Dropbox: OAuth-linked remotes. */
final class CloudDrives {

    private CloudDrives() {}

    static Backend open(String name, String type, XBConfig.Remote settings, String prefix, Remotes.Context context)
        throws IOException {
        throw new IOException("remote " + name + ": " + type + " isn't available in this version of xBackup yet.");
    }
}
