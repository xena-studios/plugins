package co.xenastudios.core;

/** What the core reads from every plugin's config record. */
public interface XenaConfig {

    ErrorMessages errorMessages();

    UpdateCheckerSettings updateChecker();
}
