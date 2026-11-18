package co.xenastudios.xstats.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The website's script never turns text into HTML: everything from the server (names,
 * the owner's text, other plugins' sections) goes in as textContent. This keeps it that way.
 */
class SiteTest {

    static final Path SITE = Path.of(System.getProperty("xena.repoRoot"), "xstats", "web");

    @Test
    void theScriptNeverWritesHtml() throws Exception {
        String script = Files.readString(SITE.resolve("app.js"));
        for (String forbidden : List.of("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "eval(", "new Function",
            "setAttribute(\"on", "javascript:", ".style.cssText", "setAttribute(\"style\"")) {
            assertFalse(script.contains(forbidden), forbidden);
        }
        assertTrue(script.contains("textContent"));
    }

    @Test
    void thePageHasNoInlineScriptOrStyle() throws Exception {
        String page = Files.readString(SITE.resolve("index.html"));
        assertFalse(page.matches("(?s).*<script(?![^>]*\\bsrc=)[^>]*>.*"), "inline script");
        assertFalse(page.contains("<style"));
        assertFalse(page.contains(" style="));
        assertFalse(page.matches("(?s).*\\son[a-z]+=.*"), "inline handlers");
    }

    @Test
    void theSiteIsSmall() throws Exception {
        long bytes = 0;
        for (String name : Site.names()) {
            bytes += Files.size(SITE.resolve(name));
        }
        assertTrue(bytes < 200 * 1024, bytes + " bytes");
    }
}
