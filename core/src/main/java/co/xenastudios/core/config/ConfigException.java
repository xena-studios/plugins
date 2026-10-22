package co.xenastudios.core.config;

/** The config file couldn't be read at all, such as a YAML syntax error. */
public final class ConfigException extends Exception {

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
