package co.xenastudios.core;

import co.xenastudios.core.config.Comment;
import co.xenastudios.core.text.Message;

/** The errors every plugin's commands share, under {@code messages.error}. */
public record ErrorMessages(
    @Comment("The console or a command block ran a command only players can use.") Message playerOnly,
    @Comment("The sender lacks the command's permission.") Message noPermission,
    @Comment("No online player has that name. <player> is the name typed.") Message playerNotFound,
    @Comment("The command was typed wrong. <usage> is how to type it.") Message usage,
    @Comment("The command hit an error. The console has the details.") Message failed) {

    public static final ErrorMessages DEFAULTS = new ErrorMessages(
        Message.of("<prefix><error>Only players can use this command."),
        Message.of("<prefix><error>You don't have permission to do that."),
        Message.of("<prefix><error>No player named <highlight><player></highlight> is online."),
        Message.of("<prefix><error>Usage:</error> <usage>"),
        Message.of("<prefix><error>Something went wrong. The console has the details."));
}
