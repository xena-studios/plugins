package co.xenastudios.xbackup.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** A hostile or broken reply is an IOException, never a crash. */
class JsonTest {

    @Test
    void readsWhatTheDrivesSend() throws IOException {
        Map<String, Object> reply = Json.object("{\"name\":\"a\\u00e9\",\"size\":12,\"value\":[{\"x\":true}],\"n\":null}");
        assertEquals("aé", reply.get("name"));
        assertEquals(12L, reply.get("size"));
        assertEquals(List.of(Map.of("x", true)), reply.get("value"));
    }

    @Test
    void deepNestingAndBadEscapesAreRefused() {
        assertThrows(IOException.class, () -> Json.parse("[".repeat(100_000) + "]".repeat(100_000)));
        assertThrows(IOException.class, () -> Json.parse("\"\\uZZZZ\""));
        assertThrows(IOException.class, () -> Json.parse("\"\\u12\""));
        assertEquals(List.of(List.of()), assertDoesNotOverflow("[[]]"));
    }

    private static Object assertDoesNotOverflow(String text) {
        try {
            return Json.parse(text);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
