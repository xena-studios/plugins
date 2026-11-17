package co.xenastudios.xrewind.command;

import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.db.Database;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.xrewind.XRewind;
import co.xenastudios.xrewind.log.LogQueue;
import co.xenastudios.xrewind.log.Writer;

/** {@code /xrewind status}: where the log is going, and whether it's keeping up. */
public final class StatusCommand {

    private static final Message STATUS = Message.of("""
        <prefix><highlight>Storage</highlight>: <storage>
        <highlight>Saved</highlight> since startup: <written> changes. Waiting: <waiting>.<dropped>
        <highlight>Last batch</highlight>: <batch>
        <highlight>Problem</highlight>: <problem>""");

    private StatusCommand() {}

    public static CommandSpec spec(XRewind plugin) {
        return CommandSpec.named("status")
            .permission("xrewind.status")
            .description("Whether logging is keeping up.")
            .usage("/xrewind status")
            .executes(invocation -> {
                Database database = plugin.storage().database();
                LogQueue queue = plugin.queue();
                Writer writer = plugin.writer();
                long dropped = queue.dropped();
                int size = writer.lastBatchSize();
                invocation.sender().sendMessage(STATUS.chat(
                    Placeholders.text("storage", database == null ? "not ready" : database.description()),
                    Placeholders.text("written", String.format("%,d", writer.written())),
                    Placeholders.text("waiting", String.format("%,d", queue.size())),
                    Placeholders.text("dropped", dropped == 0 ? "" : " Missed: " + String.format("%,d", dropped) + "."),
                    Placeholders.text("batch", size == 0 ? "none yet"
                        : String.format("%,d changes in %.1f ms", size, writer.lastBatchNanos() / 1e6)),
                    Placeholders.text("problem", writer.problem() == null ? "none" : writer.problem())));
            });
    }
}
