package co.xenastudios.xbackup

import co.xenastudios.core.common.i18n.CoreMessages
import co.xenastudios.core.common.i18n.MessageBundle
import co.xenastudios.core.common.i18n.MessageCatalog
import co.xenastudios.core.common.i18n.MessageKey

/**
 * Every message xBackup sends, in `lang/<language>.yml` (English here, in code): 1.x's replies,
 * word for word where 1.x had them. A reason (`<reason>`) is what went wrong, as the console says
 * it, in English: it names files and remotes.
 */
public object XBackupMessages : MessageBundle() {
    private val SNAPSHOT = listOf("id", "files", "size", "added", "chunks", "time", "pause")

    public val info: MessageKey = message(
        "info", "<prefix>xBackup <highlight><version></highlight> on <platform>: <backups> backups, <size>, in <repository>. <gray>/xbackup reload | debug",
        "/xbackup on its own.", placeholders = listOf("version", "platform", "backups", "size", "repository"),
    )
    public val failed: MessageKey = message("failed", "<prefix><error><reason></error>", "A job that failed: <reason> says why.", placeholders = listOf("reason"))
    public val skipped: MessageKey = message("skipped", "<prefix>Skipped: <reason>.", "A backup that didn't run, on purpose.", placeholders = listOf("reason"))
    public val reading: MessageKey = message("reading", "<prefix><gray>Reading the repository...</gray>", "Just after a start, before the list of backups is in memory.")

    // ---- finding a backup ----

    public val noBackups: MessageKey = message("find.none", "<prefix><error>There are no backups yet.")
    public val idTooShort: MessageKey = message("find.too-short", "<prefix><error>Give at least 4 characters of the backup's id, or latest.")
    public val idAmbiguous: MessageKey = message("find.ambiguous", "<prefix><error>More than one backup starts with <id>; give more of it.", placeholders = listOf("id"))
    public val idUnknown: MessageKey = message("find.unknown", "<prefix><error>No backup's id starts with <id>. /backup list shows them.", placeholders = listOf("id"))
    public val badPath: MessageKey = message("restore.bad-path", "<prefix><error>A restore path can't go up with \"..\".")
    public val noRemote: MessageKey = message("remote.unknown", "<prefix><error>There's no remote named <name> in config.yml.", placeholders = listOf("name"))

    // ---- /backup now ----

    public val backingUp: MessageKey = message("now.started", "<prefix><gray>Backing up...</gray>")
    public val backingUpAfter: MessageKey = message("now.queued", "<prefix>Backing up after the job that's running (<activity>).", placeholders = listOf("activity"))
    public val backedUp: MessageKey = message(
        "now.done",
        "<prefix><success>Backed up</success> <highlight><id></highlight>: <files> files, <size>, <highlight><added></highlight> new (<chunks>), in <time>. The server paused <highlight><pause> ms</highlight>.",
        "<chunks> is chunks.none, chunks.one or chunks.many.", placeholders = SNAPSHOT,
    )
    public val backedUpWarnings: MessageKey = message("now.warnings", "<prefix><error><count> warnings</error>; the console lists them.", placeholders = listOf("count"))
    public val chunksNone: MessageKey = message("chunks.none", "no chunks changed")
    public val chunksOne: MessageKey = message("chunks.one", "1 changed chunk")
    public val chunksMany: MessageKey = message("chunks.many", "<count> changed chunks", placeholders = listOf("count"))

    // ---- /backup list ----

    public val listEmpty: MessageKey = message("list.empty", "<prefix>No backups yet. <highlight>/backup now</highlight> makes one.")
    public val listHeader: MessageKey = message("list.header", "<prefix><highlight><count></highlight> backups, <size> in all. Click one to restore it.", placeholders = listOf("count", "size"))
    public val listLine: MessageKey = message(
        "list.line",
        "<click:suggest_command:'/backup restore <id> '><hover:show_text:'<time>'><highlight><id></highlight></hover></click> <gray><ago></gray> <reason> <gray>·</gray> <size> <gray>+<added></gray><pinned><warned>",
        "One backup. <time> is when, in full; <ago> is ago or just-now; <pinned> list.pinned or nothing, <warned> list.warned or nothing.",
        placeholders = listOf("id", "time", "ago", "reason", "size", "added", "pinned", "warned"),
    )
    public val listPinned: MessageKey = message("list.pinned", " <success>pinned</success>")
    public val listWarned: MessageKey = message("list.warned", " <error>!</error>")
    public val listNext: MessageKey = message(
        "list.next", "<gray>Page <page> of <pages></gray> <click:run_command:'/backup list <next>'><highlight>Next page »</highlight></click>",
        placeholders = listOf("page", "pages", "next"),
    )
    public val ago: MessageKey = message("ago", "<time> ago", placeholders = listOf("time"))
    public val justNow: MessageKey = message("just-now", "just now")

    // ---- /backup status ----

    public val statusRepository: MessageKey = message("status.repository", "<prefix><highlight>Repository</highlight>: <path>, <count> backups, <size>", placeholders = listOf("path", "count", "size"))
    public val statusLastNone: MessageKey = message("status.last-none", "<highlight>Last backup</highlight>: none yet")
    public val statusLast: MessageKey = message("status.last", "<highlight>Last backup</highlight>: <ago>, <files> files, +<added> in <time>", placeholders = listOf("ago", "files", "added", "time"))
    public val statusPause: MessageKey = message("status.pause", "<highlight>Server pause</highlight>: <pause> ms saving, autosave off for <off>", placeholders = listOf("pause", "off"))
    public val statusNextOff: MessageKey = message("status.next-off", "<highlight>Next backup</highlight>: off (/backup now only)")
    public val statusNext: MessageKey = message("status.next", "<highlight>Next backup</highlight>: in <time>", placeholders = listOf("time"))
    public val statusRunningNone: MessageKey = message("status.running-none", "<highlight>Running</highlight>: nothing")
    public val statusRunning: MessageKey = message("status.running", "<highlight>Running</highlight>: <activity>", placeholders = listOf("activity"))
    public val statusCheckNever: MessageKey = message("status.check-never", "<highlight>Last check</highlight>: never")
    public val statusCheck: MessageKey = message("status.check", "<highlight>Last check</highlight>: <ago>, <result>", placeholders = listOf("ago", "result"))
    public val statusRemote: MessageKey = message("status.remote", "<highlight>Remote <name></highlight> (<type>): <state>", placeholders = listOf("name", "type", "state"))
    public val statusRemoteOff: MessageKey = message("status.remote-off", "off")
    public val statusRemoteNever: MessageKey = message("status.remote-never", "not copied since the server started")
    public val statusFamily: MessageKey = message(
        "status.family", "<highlight>Databases copied by their plugin</highlight>: <list>",
        "The Xena plugins whose SQLite databases each backup has their own writer copy.", placeholders = listOf("list"),
    )
    public val statusExternal: MessageKey = message(
        "status.external", "<highlight>Not in the backups</highlight>: <list>. Back those up with the database's own tools (mysqldump).",
        "Plugins that keep their data in MySQL or MariaDB, outside the server.", placeholders = listOf("list"),
    )
    public val statusProblemNone: MessageKey = message("status.problem-none", "<highlight>Problem</highlight>: none")
    public val statusProblem: MessageKey = message("status.problem", "<highlight>Problem</highlight>: <error><problem></error>", placeholders = listOf("problem"))
    public val statusRestore: MessageKey = message(
        "status.restore", "<prefix><highlight>Restore waiting</highlight>: backup <id> (<count> paths), on the next start. <highlight>/backup restore cancel</highlight> to call it off.",
        placeholders = listOf("id", "count"),
    )

    // ---- /backup verify ----

    public val verifyingAll: MessageKey = message("verify.started-full", "<prefix><gray>Checking everything...</gray>")
    public val verifyingSample: MessageKey = message("verify.started", "<prefix><gray>Checking a sample...</gray>")
    public val verifyOk: MessageKey = message(
        "verify.ok", "<prefix><success>All good.</success> Read back <checked> of <total> packs (<size>) across <backups> backups.",
        placeholders = listOf("checked", "total", "size", "backups"),
    )
    public val verifyProblems: MessageKey = message(
        "verify.problems", "<prefix><error>Found <count> problems</error>, the first: <first> The console lists them all; <highlight>/backup repair</highlight> rebuilds the index if that's what's damaged.",
        placeholders = listOf("count", "first"),
    )

    // ---- /backup restore ----

    public val restoreCancelled: MessageKey = message("restore.cancelled", "<prefix><success>Cancelled</success> the restore that was waiting for the next start.")
    public val restoreNoneWaiting: MessageKey = message("restore.none", "<prefix>No restore is waiting.")
    public val restoreEverything: MessageKey = message("restore.everything", "everything", "What a restore with no path restores, in restore.plan and restore.staging.")
    public val restorePlan: MessageKey = message(
        "restore.plan",
        "<prefix>Restoring <highlight><what></highlight> from backup <highlight><id></highlight> (<ago>) would change <changed> files<chunks>, bring back <added> and set aside <removed>. Confirm to stage it: it's swapped in on the next start, before any world loads, and what's there now is kept in <replaced>.",
        "<chunks> is restore.chunks, or nothing when no region chunk changes.",
        placeholders = listOf("what", "id", "ago", "changed", "chunks", "added", "removed", "replaced"),
    )
    public val restoreChunks: MessageKey = message("restore.chunks", " (<count> region chunks)", placeholders = listOf("count"))
    public val restoreExample: MessageKey = message("restore.example", "<gray><line></gray>", "One changed path: + comes back, ~ changes, - is set aside.", placeholders = listOf("line"))
    public val restoreButton: MessageKey = message(
        "restore.button", "<click:run_command:'<command>'><hover:show_text:'<command>'><success>[Restore it]</success></hover></click>", placeholders = listOf("command"),
    )
    public val restoreStaging: MessageKey = message("restore.staging", "<prefix><gray>Staging <what> from backup <id>...</gray>", placeholders = listOf("what", "id"))
    public val restoreStaged: MessageKey = message(
        "restore.staged",
        "<prefix><success>Staged</success> <files> files (<size>), each checked against its backup. <highlight>Restart the server</highlight> to put them in place. <highlight>/backup restore cancel</highlight> calls it off.",
        placeholders = listOf("files", "size"),
    )
    public val restoreJars: MessageKey = message(
        "restore.jars",
        "<prefix><error><count> plugin jars are in it (<first>).</error> The server loads plugin jars before xBackup puts files back, so these take effect only from the start after. To restore jars at once, stop the server and use the command-line restore.",
        placeholders = listOf("count", "first"),
    )

    // ---- /backup prune, pin, unpin, forget ----

    public val pruning: MessageKey = message("prune.started", "<prefix><gray>Cleaning up...</gray>")
    public val pruned: MessageKey = message("prune.done", "<prefix><success>Removed</success> <removed> backups retention doesn't keep (<kept> kept), and gave back <size>.", placeholders = listOf("removed", "kept", "size"))
    public val pinned: MessageKey = message("pin.pinned", "<prefix><success>Pinned</success> <id>: retention will always keep it.", placeholders = listOf("id"))
    public val unpinned: MessageKey = message("pin.unpinned", "<prefix>Unpinned <id>: retention can remove it again.", placeholders = listOf("id"))
    public val forgetConfirm: MessageKey = message(
        "forget.confirm",
        "<prefix>This removes backup <highlight><id></highlight> (<ago>) for good. <click:run_command:'<command>'><hover:show_text:'<command>'><error>[Remove it]</error></hover></click>",
        placeholders = listOf("id", "ago", "command"),
    )
    public val forgotten: MessageKey = message("forget.done", "<prefix>Removed backup <id>. Its space comes back at the next clean-up (<highlight>/backup prune</highlight>).", placeholders = listOf("id"))

    // ---- /backup copy ----

    public val copying: MessageKey = message("copy.started", "<prefix><gray>Copying to <name>...</gray>", placeholders = listOf("name"))
    public val copied: MessageKey = message("copy.done", "<prefix><success>Copied</success> to <name>: <sent> files (<size>) sent, <removed> removed.", placeholders = listOf("name", "sent", "size", "removed"))
    public val copyHeld: MessageKey = message("copy.held", "<prefix><error>Kept some files: <held>.</error>", placeholders = listOf("held"))
    public val tidyNeedsForget: MessageKey = message("tidy.needs-forget", "<prefix><error>Removing a remote's backups needs xbackup.forget as well as xbackup.copy.</error>")
    public val tidyLooking: MessageKey = message("tidy.looking", "<prefix><gray>Looking at <name>...</gray>", placeholders = listOf("name"))
    public val tidyApplying: MessageKey = message("tidy.applying", "<prefix><gray>Copying to <name>, then tidying it...</gray>", placeholders = listOf("name"))
    public val tidied: MessageKey = message(
        "tidy.done", "<prefix><success>Tidied</success> <name>: removed <backups> backups only it had, and <packs> packs (<size>).",
        placeholders = listOf("name", "backups", "packs", "size"),
    )
    public val tidiedKept: MessageKey = message("tidy.kept", "<prefix>Kept <packs> packs that hold data backups here need and this server lost.", placeholders = listOf("packs"))
    public val tidyNothing: MessageKey = message("tidy.nothing", "<prefix><name> has nothing this server's repository doesn't.", placeholders = listOf("name"))
    public val tidyHeader: MessageKey = message("tidy.header", "<prefix><name> has <highlight><count></highlight> backups this server doesn't", placeholders = listOf("name", "count"))
    public val tidyLine: MessageKey = message("tidy.line", " <gray><id></gray> <ago> (<reason>)", placeholders = listOf("id", "ago", "reason"))
    public val tidyMore: MessageKey = message("tidy.more", " and <count> more", placeholders = listOf("count"))
    public val tidyFooter: MessageKey = message(
        "tidy.footer",
        "Removing them frees about <size> there<kept>. They can't be restored after. <click:run_command:'<command>'><hover:show_text:'<command>'><error>[Remove them]</error></hover></click>",
        "<kept> is tidy.keeping, or nothing.", placeholders = listOf("size", "kept", "command"),
    )
    public val tidyKeeping: MessageKey = message("tidy.keeping", ", keeping <packs> packs backups here need", placeholders = listOf("packs"))

    // ---- /backup link ----

    public val notADrive: MessageKey = message("link.not-a-drive", "<prefix><error><name> is a <type> remote; only drive, onedrive and dropbox are linked.", placeholders = listOf("name", "type"))
    public val linkDropbox: MessageKey = message(
        "link.dropbox",
        "<prefix>Open <click:open_url:'<url>'><highlight><underlined>this link</underlined></highlight></click>, allow xBackup, then run <highlight>/backup link <name> \\<code></highlight> with the code Dropbox shows.",
        placeholders = listOf("url", "name"),
    )
    public val linkDevice: MessageKey = message(
        "link.device", "<prefix>Go to <click:open_url:'<url>'><highlight><underlined><url></underlined></highlight></click> on any device and enter <highlight><code></highlight>. Waiting...",
        placeholders = listOf("url", "code"),
    )
    public val linked: MessageKey = message(
        "link.done", "<prefix><success>Linked</success> <name>. Backups are copied to it from the next one; <highlight>/backup copy <name></highlight> copies them now.", placeholders = listOf("name"),
    )
    public val linkFailed: MessageKey = message("link.failed", "<prefix><error>Couldn't link <name>: <reason></error>", placeholders = listOf("name", "reason"))

    // ---- /backup repair ----

    public val repairing: MessageKey = message("repair.started", "<prefix><gray>Rebuilding the index from the packs...</gray>")
    public val repaired: MessageKey = message("repair.done", "<prefix><success>Rebuilt</success> the index: <blobs> pieces in <packs> packs.", placeholders = listOf("blobs", "packs"))
    public val repairDamaged: MessageKey = message(
        "repair.damaged", "<prefix><error><count> packs are damaged</error>; the console lists them. Run <highlight>/backup verify full</highlight> to see which backups that touches.",
        placeholders = listOf("count"),
    )

    // ---- broadcasts ----

    public val broadcastStart: MessageKey = message("broadcast.start", "<prefix><gray>Backing up the server...</gray>", "To everyone online, when broadcasts.start is on.")
    public val broadcastDone: MessageKey = message("broadcast.done", "<prefix><gray>Backed up, in <time>.</gray>", "To everyone online, when broadcasts.done is on.", placeholders = listOf("time"))

    /** Every message the lang files hold: the core's, then xBackup's own. */
    public val catalog: MessageCatalog = MessageCatalog.of(CoreMessages, this)
}
