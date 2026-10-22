package co.xenastudios.core.command;

/** What a command does when it runs. */
@FunctionalInterface
public interface Executor {

    /**
     * Runs the command. Throw {@link CommandFailure} (usually through an
     * {@link Invocation} helper) to stop with an error message.
     */
    void execute(Invocation invocation);
}
