package co.xenastudios.core;

import co.xenastudios.core.text.Theme;

/** What the core reads from every plugin's config record. */
public interface XenaConfig {

    Theme theme();

    ErrorMessages errorMessages();

    UpdateCheckerSettings updateChecker();
}
