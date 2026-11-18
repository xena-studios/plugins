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
import java.nio.file.StandardOpenOption;
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
                if (e instanceof java.nio.file.NoSuchFileException || e instanceof Permanent || e instanceof ShortFile) {
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

    /** The file really is shorter than asked for: trying again won't make it longer. */
    static final class ShortFile extends java.io.EOFException {
        ShortFile(String file, long end) {
            super(file + " is shorter than " + end + " bytes");
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
        if (size == 0) {
            // An empty file, such as a pin: the JDK refuses a stream publisher of length 0.
            return HttpRequest.BodyPublishers.noBody();
        }
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
     * whole file, so the range is cut from it; a 206 must say it starts where asked.
     */
    static byte[] ranged(HttpResponse<byte[]> response, String file, long offset, int length) throws IOException {
        byte[] body = response.body();
        if (response.statusCode() == 200) {
            if (body.length < offset + length) {
                throw new ShortFile(file, offset + length);
            }
            return offset == 0 && body.length == length ? body
                : java.util.Arrays.copyOfRange(body, (int) offset, (int) offset + length);
        }
        String range = response.headers().firstValue("Content-Range").orElse(null);
        if (range != null) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("bytes\\s+(\\d+)-(\\d+)/.*").matcher(range.strip());
            if (!matcher.matches() || Long.parseLong(matcher.group(1)) != offset) {
                throw new Transient(file + ": the server sent bytes " + range + " when asked for " + offset + "-"
                    + (offset + length - 1));
            }
        }
        if (body.length != length) {
            throw new ShortFile(file, offset + length);
        }
        return body;
    }

    /** A Range header's value for {@code length} bytes from {@code offset}. */
    static String range(long offset, int length) {
        return "bytes=" + offset + "-" + (offset + length - 1);
    }

    static HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
        return await(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    /** A downloaded file: its status, and its body from a temporary file deleted on close. */
    record Download(int status, InputStream body) {}

    /** A GET for a whole file, saved to a temporary file first so a stalled body can't hang. */
    static Download download(HttpRequest request) throws IOException, InterruptedException {
        Path temp = Files.createTempFile("xbackup-get", ".part");
        try {
            HttpResponse<Path> response = await(request, HttpResponse.BodyHandlers.ofFile(temp));
            return new Download(response.statusCode(), Files.newInputStream(temp, StandardOpenOption.DELETE_ON_CLOSE));
        } catch (IOException | InterruptedException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /** How long a server may go quiet in the middle of a reply before it's given up on. Tests shorten it. */
    static volatile Duration idle = Duration.ofMinutes(2);

    /**
     * Sends and waits for the whole reply. The request's own timeout covers the wait for the
     * reply to begin (an upload included); after that, a reply that stops arriving for {@link
     * #idle} is cancelled, so a dead connection can't hold xBackup's only worker for good.
     */
    static <T> HttpResponse<T> await(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws IOException, InterruptedException {
        java.util.concurrent.atomic.AtomicLong lastData = new java.util.concurrent.atomic.AtomicLong();
        HttpResponse.BodyHandler<T> watched = info -> {
            lastData.set(System.nanoTime());
            return new Watched<>(handler.apply(info), lastData);
        };
        java.util.concurrent.CompletableFuture<HttpResponse<T>> reply = CLIENT.sendAsync(request, watched);
        Duration limit = idle;
        long poll = Math.max(100, Math.min(5_000, limit.toMillis() / 4));
        while (true) {
            try {
                return reply.get(poll, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                long since = lastData.get();
                if (since != 0 && System.nanoTime() - since > limit.toNanos()) {
                    reply.cancel(true);
                    throw new java.net.http.HttpTimeoutException("the server stopped sending for "
                        + limit.toSeconds() + " s");
                }
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof IOException io) {
                    throw io;
                }
                throw new IOException(cause.getMessage(), cause);
            } catch (InterruptedException e) {
                reply.cancel(true);
                throw e;
            }
        }
    }

    // Notes the time of every piece of a reply's body as it arrives.
    private record Watched<T>(HttpResponse.BodySubscriber<T> inner, java.util.concurrent.atomic.AtomicLong lastData)
        implements HttpResponse.BodySubscriber<T> {

        @Override
        public java.util.concurrent.CompletionStage<T> getBody() {
            return inner.getBody();
        }

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            inner.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<java.nio.ByteBuffer> item) {
            lastData.set(System.nanoTime());
            inner.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            inner.onError(throwable);
        }

        @Override
        public void onComplete() {
            inner.onComplete();
        }
    }

    /**
     * A timeout for sending this many bytes: two minutes, plus the time it takes at 16 KiB/s
     * or at the upload limit if that's slower, with room to spare. A fixed one would be too
     * short for a big pack on a slow uplink.
     */
    static Duration uploadTimeout(long bytes, Throttle throttle) {
        long rate = 16 * 1024;
        if (throttle.bytesPerSecond() > 0 && throttle.bytesPerSecond() < rate) {
            rate = throttle.bytesPerSecond();
        }
        return Duration.ofSeconds(120 + bytes * 3 / 2 / rate);
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

    /**
     * Decodes a URL path's percent escapes as UTF-8. Unlike {@code URLDecoder}, which decodes
     * forms, a {@code +} stays a {@code +}: in a path it's a plus, not a space.
     */
    static String decodePath(String raw) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length());
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '%' && i + 2 < raw.length()) {
                int high = Character.digit(raw.charAt(i + 1), 16);
                int low = Character.digit(raw.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    out.write(high << 4 | low);
                    i += 3;
                    continue;
                }
            }
            int codePoint = raw.codePointAt(i);
            byte[] bytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
            i += Character.charCount(codePoint);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

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
