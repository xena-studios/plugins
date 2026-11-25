package co.xenastudios.core.loader;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * A {@code java.util.logging} logger that hands each line to the platform's own, for the loader,
 * which logs through JUL, on a platform that gives a plugin another kind of logger (Velocity's
 * SLF4J): {@code SEVERE} as an error, {@code WARNING} as a warning, the rest as info.
 */
public final class JulBridge {

    /** Where a line goes, by its level. */
    public interface Sink {
        void info(String message);

        void warn(String message, Throwable cause);

        void error(String message, Throwable cause);
    }

    private JulBridge() {}

    /** A logger called {@code name} whose lines go only to {@code sink}. */
    public static Logger to(String name, final Sink sink) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.INFO);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                int level = record.getLevel().intValue();
                String message = record.getMessage();
                if (level >= Level.SEVERE.intValue()) {
                    sink.error(message, record.getThrown());
                } else if (level >= Level.WARNING.intValue()) {
                    sink.warn(message, record.getThrown());
                } else if (level >= Level.INFO.intValue()) {
                    sink.info(message);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        });
        return logger;
    }
}
