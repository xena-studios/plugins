package co.xenastudios.core.text;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class QuotedTest {

    @Test
    void escapesWhatAReaderOrAPageCouldTripOn() {
        assertEquals("\"plain\"", Quoted.of("plain"));
        assertEquals("\"a\\\"b\\\\c\"", Quoted.of("a\"b\\c"));
        assertEquals("\"\\n\\r\\t\\u0000\\u001f\\u007f\"", Quoted.of("\n\r\t\u0000\u001f\u007f"));
        assertEquals("\"\\u003c/script\\u003e \\u0026 \\u2028\\u2029\"", Quoted.of("</script> & \u2028\u2029"));
        assertEquals("\"héllo ✓\"", Quoted.of("héllo ✓"), "other text stays as it is");
    }

    @Test
    void appendsToWhatsThere() {
        StringBuilder out = new StringBuilder("key: ");
        Quoted.append(out, "x");
        assertEquals("key: \"x\"", out.toString());
    }
}
