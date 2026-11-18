package co.xenastudios.xstats.web;

import co.xenastudios.xstats.XStats;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code /xstats web export}: the website as plain files, to host anywhere (GitHub Pages,
 * Cloudflare Pages, your own domain), with a {@code config.json} pointing at this server's API.
 */
public final class Export {

    private Export() {}

    /** Writes the files to plugins/xStats/site/. Off the server's threads. */
    public static Path run(XStats plugin) throws IOException {
        Path folder = plugin.getDataFolder().toPath().resolve("site");
        Files.createDirectories(folder);
        for (String name : Site.names()) {
            Files.write(folder.resolve(name), Site.read(plugin.getClass().getClassLoader(), name));
        }
        String api = plugin.config().web().publicUrl().strip();
        if (!api.matches("https?://[^\\s\"'<>\\\\]+")) {
            api = "";
        }
        while (api.endsWith("/")) {
            api = api.substring(0, api.length() - 1);
        }
        Files.writeString(folder.resolve("config.json"), new Json().object().field("api", api).end() + "\n", StandardCharsets.UTF_8);
        return folder;
    }
}
