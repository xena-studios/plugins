package co.xenastudios.xbackup.config;

import co.xenastudios.core.ErrorMessages;
import co.xenastudios.core.UpdateCheckerSettings;
import co.xenastudios.core.XenaConfig;
import co.xenastudios.core.command.Toggle;
import co.xenastudios.core.config.Comment;
import co.xenastudios.core.config.ConfigSpec;
import co.xenastudios.core.config.Range;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.TextStyle;
import co.xenastudios.core.text.Theme;
import java.util.List;
import java.util.Map;

/** xBackup's config.yml. Its defaults are {@link #DEFAULTS}; the docs show the file they produce. */
public record XBConfig(
    @Comment({
        "The look xBackup's messages share. The default messages use these as tags, so changing",
        "a colour here restyles them all. Write a style as MiniMessage tags: <gray>,",
        "<#5AA9FF>, or <gold><bold>."})
    Theme theme,
    @Comment("What players and staff are told.") Messages messages,
    @Comment({
        "/backup: run, list, check and restore backups. Each subcommand has its own permission,",
        "xbackup.<subcommand>, which operators have by default."})
    Command command,
    @Comment({
        "Where backups are kept: a folder, relative to the server folder or absolute. Put it on",
        "a second disk if you have one. Changing it starts a new, empty repository there; the",
        "old one is left as it is."})
    String repository,
    @Comment("When backups run.") Schedule schedule,
    @Comment("What's backed up.") Sources sources,
    @Comment({
        "Paths to leave out, relative to the server folder. * is anything but /, ** is anything,",
        "so \"plugins/*/logs/**\" leaves out every plugin's logs folder."})
    List<String> exclude,
    @Comment({
        "Which backups to keep. Each tier keeps the newest backup of each of its last so many",
        "hours, days, weeks or months; a backup can count for several. The newest backup and",
        "pinned ones (/backup pin) are always kept. Space is given back once a day."})
    Retention retention,
    @Comment({
        "Keeping backups cheap to run. Hashing and compressing happen off the server's threads;",
        "these keep them from crowding the disk or the network."})
    Performance performance,
    @Comment({
        "Each backup checks what it just wrote. This also reads back a share of the older data",
        "now and then, so damage on the disk is found before you need a restore."})
    Verify verify,
    @Comment({
        "Copies of the repository somewhere else, updated after each backup. Only new files are",
        "sent. Name each one; the docs list every type and its settings:",
        "https://plugins.xenastudios.co/xbackup/remotes/"})
    Map<String, Remote> remotes,
    @Comment("Tells you when a new version is out.") UpdateCheckerSettings updateChecker) implements XenaConfig {

    @Override
    public ErrorMessages errorMessages() {
        return messages.error();
    }

    public record Messages(
        ErrorMessages error,
        @Comment("Told to everyone online when a backup starts. Blank says nothing.") Message broadcastStart,
        @Comment("Told to everyone online when a backup is done. <time> is how long it took. Blank says nothing.")
        Message broadcastDone) {}

    public record Command(boolean enabled, List<String> aliases) implements Toggle {
        @Override
        public String permission() {
            // Each subcommand checks its own.
            return "";
        }
    }

    public record Schedule(
        @Comment({
            "How often a backup runs, such as 1h, 30m or 6h. \"off\" runs them only when asked",
            "(/backup now). Counted from the last backup, so a restart doesn't reset it."})
        String every,
        @Comment("Skip a scheduled backup when nobody has been online since the last one.") boolean skipWhenIdle) {}

    public record Sources(
        @Comment("Every loaded world's save folder, with its nether and end.") boolean worlds,
        @Comment("The plugins folder: every plugin's data and jar. xBackup's own repository is never included.")
        boolean plugins,
        @Comment({
            "The server's own settings: server.properties, the .yml, .json, .toml and .txt files",
            "beside it (ops, whitelist, bans), and the config folder."})
        boolean serverFiles,
        @Comment("More paths to back up, relative to the server folder, such as an unloaded world.")
        List<String> extra) {}

    public record Retention(
        @Comment("Always keep this many of the newest backups, however close together.")
        @Range(min = 0, max = 10_000) int last,
        @Range(min = 0, max = 10_000) int hourly,
        @Range(min = 0, max = 10_000) int daily,
        @Range(min = 0, max = 10_000) int weekly,
        @Range(min = 0, max = 10_000) int monthly,
        @Comment({
            "The most space the repository may use, such as 50GB. Past it, the oldest backups go",
            "first. \"off\" for no limit."})
        String maxSize,
        @Comment({
            "Free space a backup always leaves on the repository's disk. A backup that would go",
            "under it removes old backups first, and if that's not enough, doesn't start."})
        String minFreeSpace) {}

    public record Performance(
        @Comment("The most a backup reads from disk per second, such as 50MB. \"off\" for no limit.")
        String readLimit,
        @Comment("The most sent to remotes per second, such as 5MB. \"off\" for no limit.") String uploadLimit,
        @Comment({
            "Deflate level for files that compress, 1 (fastest) to 9 (smallest). Region files are",
            "already compressed and stored as they are."})
        @Range(min = 1, max = 9) int compressionLevel,
        @Comment({
            "The longest autosave may stay off for a backup. If reading the worlds takes longer,",
            "autosave goes back on and that backup is abandoned. Nothing is lost either way."})
        String maxPause) {}

    public record Verify(
        @Comment("How often to read back older data, such as 7d. \"off\" never does.") String every,
        @Comment("What share of the data each check reads, in percent.") @Range(min = 1, max = 100) int percent) {}

    /**
     * A remote. One record for every type; each type reads the settings it needs, and the
     * docs list them.
     */
    public record Remote(
        @Comment("local, s3, webdav, sftp, ftp, drive, onedrive or dropbox.") String type,
        boolean enabled,
        @Comment("Folder on the remote (or, for local, on this machine) the copy goes in.") String path,
        @Comment("s3: the endpoint, such as https://s3.eu-central-1.amazonaws.com. webdav: the folder's URL.")
        String url,
        @Comment("s3: the region, such as us-east-1 (auto for Cloudflare R2).") String region,
        @Comment("s3: the bucket.") String bucket,
        @Comment("s3: true for path-style URLs, which MinIO and some others need.") boolean pathStyle,
        @Comment({
            "s3: the access key id. sftp, ftp, webdav: the user name. Passwords and secret keys go",
            "in secrets.yml, beside this file, which only the server's user can read and which is",
            "never backed up or copied to a remote."})
        String username,
        @Comment("sftp, ftp: the server's name or address.") String host,
        @Comment("sftp, ftp: the port; 0 for the usual one.") @Range(min = 0, max = 65_535) int port,
        @Comment({
            "sftp: a private key file, relative to xBackup's folder, instead of a password. It's",
            "never backed up. A passphrase for it goes in secrets.yml as the password."})
        String privateKey,
        @Comment({
            "sftp: the server's key fingerprint (SHA256:...), checked on every connection. Blank",
            "trusts the key seen on the first connection and remembers it. ftp with TLS: the",
            "server's certificate fingerprint (SHA256:...), for a self-signed certificate. Blank",
            "trusts only certificates Java trusts, for the right host name."})
        String hostKey,
        @Comment("ftp: none, explicit (FTPS, AUTH TLS) or implicit (FTPS on port 990).") String security,
        @Comment({
            "drive, onedrive, dropbox: your app's client id. The docs show how to get one. Google",
            "Drive's client secret goes in secrets.yml."})
        String clientId) {}

    static final Remote REMOTE_TEMPLATE = new Remote("", true, "xbackup", "", "", "", false, "", "", 0, "", "",
        "explicit", "");

    public static final XBConfig DEFAULTS = new XBConfig(
        new Theme("<#5AA9FF>»</#5AA9FF> ", TextStyle.parse("<gray>"), TextStyle.parse("<#5AA9FF>"),
            TextStyle.parse("<#5EE08A>"), TextStyle.parse("<#FF6B6B>")),
        new Messages(ErrorMessages.DEFAULTS, Message.empty(), Message.empty()),
        new Command(true, List.of("backups")),
        "plugins/xBackup/repository",
        new Schedule("1h", true),
        new Sources(true, true, true, List.of()),
        List.of("**/session.lock", "plugins/.paper-remapped/**", "plugins/*/logs/**", "plugins/*/cache/**",
            "plugins/bStats/**", "**/*.tmp", "**/*.lck"),
        new Retention(3, 24, 7, 4, 6, "off", "2GB"),
        new Performance("off", "off", 6, "15m"),
        new Verify("7d", 10),
        Map.of(),
        UpdateCheckerSettings.DEFAULTS);

    public static final ConfigSpec<XBConfig> SPEC = ConfigSpec.builder(XBConfig.class, DEFAULTS)
        .header(
            "xBackup: incremental backups that cost almost nothing, and a restore you can trust.",
            "Docs: https://plugins.xenastudios.co/xbackup/configuration/",
            "",
            "Apply changes with /xbackup reload. A bad value falls back to its default with a",
            "warning in the console; xBackup never stops over config. All text is MiniMessage:",
            "https://plugins.xenastudios.co/start/formatting/")
        .template(Remote.class, REMOTE_TEMPLATE)
        .version(1)
        .build();
}
