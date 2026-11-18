package co.xenastudios.xstats.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.zip.CRC32;
import java.util.zip.GZIPOutputStream;

/**
 * A response made once and sent many times: its bytes, the same gzipped, and an ETag, so a
 * browser that has it already gets a 304 with no body.
 *
 * @param gzip the body gzipped, or null when that wouldn't be smaller
 * @param maxAge seconds a browser or proxy may keep it
 */
public record Response(int status, String type, byte[] body, byte[] gzip, String etag, int maxAge) {

    public static final String JSON = "application/json; charset=utf-8";

    public static Response json(int status, String json, int maxAge) {
        return of(status, JSON, json.getBytes(StandardCharsets.UTF_8), maxAge);
    }

    public static Response json(Json json, int maxAge) {
        return json(200, json.toString(), maxAge);
    }

    public static Response error(int status, String message) {
        return json(status, new Json().object().field("error", message).end().toString(), 0);
    }

    public static Response of(int status, String type, byte[] body, int maxAge) {
        CRC32 crc = new CRC32();
        crc.update(body);
        String etag = "\"" + HexFormat.of().toHexDigits((int) crc.getValue()) + Integer.toHexString(body.length) + "\"";
        return new Response(status, type, body, compressible(type) && body.length > 512 ? gzip(body) : null, etag, maxAge);
    }

    private static boolean compressible(String type) {
        return type.startsWith("text/") || type.startsWith("application/json") || type.startsWith("application/javascript")
            || type.startsWith("image/svg");
    }

    private static byte[] gzip(byte[] body) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length / 3 + 64);
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(body);
        } catch (IOException e) {
            return null;
        }
        byte[] zipped = out.toByteArray();
        return zipped.length < body.length ? zipped : null;
    }
}
