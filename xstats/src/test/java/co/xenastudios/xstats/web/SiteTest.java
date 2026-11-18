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
        // el() builds every element, and refuses handlers and inline styles whatever it's handed,
        // so a new attribute can't slip past the checks above.
        assertTrue(script.contains("if (/^on/i.test(key) || key.toLowerCase() === \"style\") throw"), "el()'s guard");
        assertFalse(script.matches("(?s).*[{,]\\s*\"?(on[a-z]+|style)\"?\\s*:.*"), "a handler or style passed to el()");
        assertFalse(script.contains("createElement(") && script.indexOf("createElement(") != script.lastIndexOf("createElement("),
            "elements are made only through el()");
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
