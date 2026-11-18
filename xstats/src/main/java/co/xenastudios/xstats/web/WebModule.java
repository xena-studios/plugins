package co.xenastudios.xstats.web;

import co.xenastudios.core.Module;
import co.xenastudios.xstats.XStats;
import co.xenastudios.xstats.config.XSConfig;
import java.io.IOException;

/** The web server, running while {@code web.enabled} is on. A change to any web setting restarts it on reload. */
public final class WebModule implements Module {

    private final XStats plugin;
    private final XSConfig.Web settings;
    private WebServer server;

    public WebModule(XStats plugin, XSConfig.Web settings) {
        this.plugin = plugin;
        this.settings = settings;
    }

    static WebServer.Options options(XSConfig.Web settings, int port) {
        return new WebServer.Options(settings.bind(), port, settings.site(), settings.corsOrigins(), settings.requestsPerMinute(),
            settings.maxConcurrent(), settings.trustedProxies(), settings.avatars().url(), settings.avatars().proxy());
    }

    @Override
    public void enable() {
        try {
            server = WebServer.start(options(settings, settings.port()), plugin.pages(), plugin.getClass().getClassLoader(),
                plugin.getLogger());
            plugin.getLogger().info("The stats website is on " + settings.bind() + ":" + server.port()
                + (settings.publicUrl().isBlank() ? "." : ", at " + settings.publicUrl() + "."));
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Couldn't start the stats website on " + settings.bind() + ":" + settings.port() + ": " + e
                + ". Is the port free, and is the address this machine's? Fix web in config.yml and reload.");
        } catch (NoClassDefFoundError e) {
            plugin.getLogger().warning("This Java has no built-in web server (jdk.httpserver), so the website can't run."
                + " Most Java builds include it; a trimmed-down one may not.");
        }
    }

    @Override
    public void disable() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    /** The server's port while it runs, or 0. */
    public int port() {
        return server == null ? 0 : server.port();
    }
}
