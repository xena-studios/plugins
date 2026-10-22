package co.xenastudios.core;

import co.xenastudios.core.config.Comment;
import co.xenastudios.core.text.Message;

/** The errors every plugin's commands share, under {@code messages.error}. */
public record ErrorMessages(
    @Comment("The console or a command block ran a command only players can use.") Message playerOnly,
    @Comment("The sender lacks the command's permission.") Message noPermission,
    @Comment("No online player has that name.") Message playerNotFound,
    @Comment("The command was typed wrong. <usage> is how to type it.") Message usage,
    @Comment("The command hit an error. The console has the details.") Message failed) {

    public static final ErrorMessages DEFAULTS = new ErrorMessages(
        Message.of("<red>Only players can use this command.</red>"),
        Message.of("<red>You don't have permission to do that.</red>"),
        Message.of("<red>That player could not be found.</red>"),
        Message.of("<red>Usage: <usage></red>"),
        Message.of("<red>Something went wrong. The console has the details.</red>"));
}
