package co.xenastudios.xbackup.remote;

import co.xenastudios.xbackup.store.Throttle;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** The pieces the HTTP remotes share: one client, retries, safe XML, percent-encoding. */
final class Http {

    private Http() {}

    static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    /** An HTTP call that should be tried again: the network, or the server, had a moment. */
    interface Call<T> {
        T run() throws IOException, InterruptedException;
    }

    /** Runs a call, trying again up to three times after a network error or a 5xx/429. */
    static <T> T retry(Call<T> call) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(1000L << attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("stopped");
                }
            }
            try {
                return call.run();
            } catch (Transient e) {
                last = e;
            } catch (java.net.http.HttpTimeoutException | java.net.ConnectException e) {
                last = e;
            } catch (IOException e) {
                if (e instanceof java.nio.file.NoSuchFileException || e instanceof Permanent) {
                    throw e;
                }
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("stopped");
            }
        }
        throw last;
    }

    /** A failure worth trying again. */
    static final class Transient extends IOException {
        Transient(String message) {
            super(message);
        }
    }

    /** A failure that won't go away by trying again: bad credentials, a missing bucket. */
    static final class Permanent extends IOException {
        Permanent(String message) {
            super(message);
        }
    }

    /** Throws the right exception for a failed response. */
    static IOException failure(String what, int status, String detail) {
        String message = what + " failed: HTTP " + status + (detail == null || detail.isBlank() ? "" : " (" + detail + ")");
        if (status >= 500 || status == 429 || status == 408) {
            return new Transient(message);
        }
        return new Permanent(message);
    }

    /** A file's bytes as a request body, slowed to the upload limit. */
    static HttpRequest.BodyPublisher file(Path file, Throttle throttle) throws IOException {
        long size = Files.size(file);
        return HttpRequest.BodyPublishers.fromPublisher(
            HttpRequest.BodyPublishers.ofInputStream(() -> {
                try {
                    return throttle.wrap(Files.newInputStream(file));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }), size);
    }

    /**
     * The bytes a ranged GET asked for. A 200 means the server ignored the range and sent the
     * whole file, so the range is cut from it.
     */
    static byte[] ranged(HttpResponse<byte[]> response, String file, long offset, int length)
        throws java.io.EOFException {
        byte[] body = response.body();
        if (response.statusCode() == 200 && body.length != length) {
            if (body.length < offset + length) {
                throw new java.io.EOFException(file + " is shorter than " + (offset + length) + " bytes");
            }
            return java.util.Arrays.copyOfRange(body, (int) offset, (int) offset + length);
        }
        if (body.length != length) {
            throw new java.io.EOFException(file + " is shorter than " + (offset + length) + " bytes");
        }
        return body;
    }

    /** A Range header's value for {@code length} bytes from {@code offset}. */
    static String range(long offset, int length) {
        return "bytes=" + offset + "-" + (offset + length - 1);
    }

    static HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    static HttpResponse<InputStream> stream(HttpRequest request) throws IOException, InterruptedException {
        return CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    static URI uri(String text) {
        return URI.create(text);
    }

    // ---------------------------------------------------------------- XML

    /** Parses XML with DTDs and external entities off, so a hostile server can't read local files. */
    static Document xml(byte[] bytes) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (ParserConfigurationException | SAXException e) {
            throw new IOException("the server's reply isn't readable XML: " + e.getMessage(), e);
        }
    }

    /** Child elements with this local name, at any depth. */
    static List<Element> all(Node node, String localName) {
        NodeList list = node instanceof Document document ? document.getElementsByTagNameNS("*", localName)
            : ((Element) node).getElementsByTagNameNS("*", localName);
        List<Element> out = new ArrayList<>(list.getLength());
        for (int i = 0; i < list.getLength(); i++) {
            out.add((Element) list.item(i));
        }
        return out;
    }

    /** The text of the first element with this local name, or null. */
    static String text(Node node, String localName) {
        List<Element> found = all(node, localName);
        return found.isEmpty() ? null : found.get(0).getTextContent();
    }

    static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- encoding

    /** RFC 3986 percent-encoding of everything but unreserved characters (and '/' if asked). */
    static String encode(String text, boolean keepSlash) {
        StringBuilder out = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_'
                || c == '.' || c == '~' || keepSlash && c == '/') {
                out.append(c);
            } else {
                out.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                    .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return out.toString();
    }
}
