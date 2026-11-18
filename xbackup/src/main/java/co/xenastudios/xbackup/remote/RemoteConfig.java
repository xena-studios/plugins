package co.xenastudios.xbackup.remote;

import co.xenastudios.xbackup.config.XBConfig;

/**
 * A remote's settings as the backends use them: its section of config.yml plus its secrets
 * from {@code secrets.yml}, which never sit in config.yml (see {@link
 * co.xenastudios.xbackup.server.Secrets}).
 *
 * @param password s3: the secret key; sftp, ftp, webdav: the password (sftp with a key: its passphrase)
 * @param clientSecret drive: the app's client secret
 */
public record RemoteConfig(String type, boolean enabled, String path, String url, String region, String bucket,
                           boolean pathStyle, String username, String password, String host, int port, String privateKey,
                           String hostKey, String security, String clientId, String clientSecret) {

    public RemoteConfig {
        password = password == null ? "" : password;
        clientSecret = clientSecret == null ? "" : clientSecret;
    }

    /** The config's settings for a remote, with its secrets. */
    public static RemoteConfig of(XBConfig.Remote remote, String password, String clientSecret) {
        return new RemoteConfig(remote.type(), remote.enabled(), remote.path(), remote.url(), remote.region(),
            remote.bucket(), remote.pathStyle(), remote.username(), password, remote.host(), remote.port(),
            remote.privateKey(), remote.hostKey(), remote.security(), remote.clientId(), clientSecret);
    }
}
