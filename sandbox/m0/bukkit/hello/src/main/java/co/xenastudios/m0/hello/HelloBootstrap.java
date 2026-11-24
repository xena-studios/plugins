package co.xenastudios.m0.hello;

import co.xenastudios.m0.loader.Bootstrap;

/** Hello's main class: the shared Java bootstrap, pointed at the Kotlin plugin. */
public final class HelloBootstrap extends Bootstrap {
    @Override
    protected String delegateClass() {
        return "co.xenastudios.m0.hello.HelloPlugin";
    }
}
