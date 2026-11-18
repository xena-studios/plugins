package co.xenastudios.zchat.command;

import co.xenastudios.core.Setup;
import co.xenastudios.core.command.CommandSpec;
import co.xenastudios.core.command.Completions;
import co.xenastudios.core.command.Invocation;
import co.xenastudios.core.text.Message;
import co.xenastudios.core.text.Placeholders;
import co.xenastudios.zchat.ZChat;
import co.xenastudios.zchat.chat.ChatFilter;
import co.xenastudios.zchat.config.Settings;
import co.xenastudios.zchat.config.ZConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.bukkit.command.CommandSender;

/**
 * /filter: manage the chat filter's words in game. The words live in config.yml's
 * filter.words, so there's one list whether it's edited here or by hand; each change is
 * saved there (comments kept) and applied at once.
 */
public final class FilterCommands {

    static final String WORDS_PATH = "filter.words";
    private static final List<String> SUBCOMMANDS = List.of("add", "remove", "list", "test");
    // A word, optionally with * at either end: no spaces, and no * in the middle.
    private static final Pattern WORD = Pattern.compile("\\*?[^\\s*]+\\*?");

    private FilterCommands() {}

    public static void register(Setup setup, ZChat zchat, ZConfig config) {
        Settings.FilterCommand settings = config.filter().command();
        if (!settings.enabled()) {
            return;
        }
        setup.command(CommandSpec.named("filter", settings)
            .description("Add, remove and list chat filter words, or test a message.")
            .usage("/filter <add|remove|list|test> [word or message]")
            .completes((sender, args) -> {
                if (args.length == 1) {
                    return Completions.matching(args[0], SUBCOMMANDS);
                }
                if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
                    return Completions.matching(args[1], zchat.config().filter().words());
                }
                return List.of();
            })
            .executes(invocation -> {
                String sub = invocation.arg(0);
                if (sub == null) {
                    throw invocation.usage();
                }
                Settings.FilterCommand current = zchat.config().filter().command();
                switch (sub.toLowerCase(Locale.ROOT)) {
                    case "add" -> change(zchat, invocation, current, true);
                    case "remove" -> change(zchat, invocation, current, false);
                    case "list" -> list(zchat, invocation.sender(), current);
                    case "test" -> test(zchat, invocation, current);
                    default -> throw invocation.usage();
                }
            }));
    }

    /** The word as the filter stores it: trimmed and lowercased, or null if it isn't one word. */
    static String normalise(String typed) {
        if (typed == null) {
            return null;
        }
        String word = typed.trim().toLowerCase(Locale.ROOT);
        return WORD.matcher(word).matches() ? word : null;
    }

    private static void change(ZChat zchat, Invocation invocation, Settings.FilterCommand settings, boolean add) {
        // One word only: "/filter add two words" is refused rather than half added.
        String typed = invocation.rest(1);
        if (typed == null) {
            throw invocation.usage();
        }
        String word = normalise(typed);
        CommandSender sender = invocation.sender();
        if (word == null) {
            settings.messageInvalid().send(sender);
            return;
        }
        AtomicBoolean changed = new AtomicBoolean();
        // Decided against the file itself, not the loaded config, so a word someone added
        // by hand (without reloading) is seen too.
        zchat.editConfig(yaml -> {
            List<String> words = new ArrayList<>(yaml.getStringList(WORDS_PATH));
            boolean present = words.stream().anyMatch(existing -> existing.trim().equalsIgnoreCase(word));
            if (add == present) {
                return false;
            }
            if (add) {
                words.add(word);
            } else {
                words.removeIf(existing -> existing.trim().equalsIgnoreCase(word));
            }
            yaml.set(WORDS_PATH, words);
            changed.set(true);
            return true;
        }, failure -> {
            if (failure != null) {
                zchat.config().messages().error().failed().send(sender);
                return;
            }
            // The config just reloaded, so its messages are the newest.
            Settings.FilterCommand now = zchat.config().filter().command();
            var placeholder = Placeholders.text("word", word);
            if (!changed.get()) {
                (add ? now.messageAlready() : now.messageNotFound()).send(sender, placeholder);
            } else if (add) {
                now.messageAdded().send(sender, placeholder);
                if (!word.startsWith("*") && !word.endsWith("*")) {
                    now.messageAddedTip().send(sender, placeholder);
                }
            } else {
                now.messageRemoved().send(sender, placeholder);
            }
        });
    }

    private static void list(ZChat zchat, CommandSender sender, Settings.FilterCommand settings) {
        List<String> words = zchat.config().filter().words().stream().map(String::trim).filter(w -> !w.isEmpty()).toList();
        if (words.isEmpty()) {
            settings.messageListEmpty().send(sender);
            return;
        }
        // Words are inserted as plain text, so a word can never be read as formatting.
        settings.messageList().send(sender,
            Placeholders.text("count", words.size()), Placeholders.text("words", String.join(", ", words)));
    }

    private static void test(ZChat zchat, Invocation invocation, Settings.FilterCommand settings) {
        String message = invocation.rest(1);
        if (message == null) {
            throw invocation.usage();
        }
        ChatFilter filter = zchat.compiled().filter();
        ChatFilter.Result result = filter.apply(message);
        Message reply = !result.matched() ? settings.messageTestPasses()
            : zchat.config().filter().mode() == Settings.FilterMode.BLOCK ? settings.messageTestBlocked()
            : settings.messageTestCensored();
        reply.send(invocation.sender(), Placeholders.text("message", result.text()));
    }
}
