package co.xenastudios.m0.hellocloud;

import co.xenastudios.m0.loader.Bootstrap;

/** HelloCloud's main class: the shared Java bootstrap, pointed at the Kotlin plugin. */
public final class HelloCloudBootstrap extends Bootstrap {
    @Override
    protected String delegateClass() {
        return "co.xenastudios.m0.hellocloud.HelloCloudPlugin";
    }
}
