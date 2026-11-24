package co.xenastudios.core.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Jars compiled at test time, and a local Maven mirror. */
final class Fixtures {

    private Fixtures() {}

    /** Compiles the sources (binary name to code) against the class path and jars the classes. */
    static Path jar(Path dir, String name, Map<String, String> sources, Path... classPath) throws IOException {
        Path src = Files.createDirectories(dir.resolve(name + "-src"));
        Path out = Files.createDirectories(dir.resolve(name + "-classes"));
        String[] args = new String[sources.size() + 4];
        args[0] = "-d";
        args[1] = out.toString();
        args[2] = "-cp";
        StringBuilder cp = new StringBuilder(".");
        for (Path path : classPath) {
            cp.append(java.io.File.pathSeparatorChar).append(path);
        }
        args[3] = cp.toString();
        int i = 4;
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = src.resolve(source.getKey().replace('.', '/') + ".java");
            Files.createDirectories(file.getParent());
            Files.write(file, source.getValue().getBytes(StandardCharsets.UTF_8));
            args[i++] = file.toString();
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, args), "compiling the fixture " + name);
        Path jar = dir.resolve(name + ".jar");
        try (JarOutputStream zip = new JarOutputStream(Files.newOutputStream(jar));
             java.util.stream.Stream<Path> files = Files.walk(out)) {
            for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                zip.putNextEntry(new JarEntry(out.relativize(file).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
        return jar;
    }

    /** A jar with one class, {@code fixture.Probe}, whose {@code hi()} says "hi". */
    static Path probeJar(Path dir) throws IOException {
        return jar(dir, "probe", Map.of("fixture.Probe",
            "package fixture; public class Probe { public static String hi() { return \"hi\"; } }"));
    }

    /** A library entry for a jar, pinned to its real hash. */
    static Library library(Path jar, String probe) throws IOException {
        return new Library("co.xenastudios.test", "probe", "1.0", Sha256.of(jar), probe);
    }

    /** A local mirror: each path prefix serves its bytes for any file, counting requests. */
    static final class Mirror implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger requests = new AtomicInteger();

        Mirror(Map<String, byte[]> contexts) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            for (Map.Entry<String, byte[]> context : contexts.entrySet()) {
                server.createContext(context.getKey(), exchange -> {
                    requests.incrementAndGet();
                    byte[] body = context.getValue();
                    if (body == null) {
                        exchange.sendResponseHeaders(404, -1);
                    } else {
                        exchange.sendResponseHeaders(200, body.length);
                        try (OutputStream out = exchange.getResponseBody()) {
                            out.write(body);
                        }
                    }
                    exchange.close();
                });
            }
            server.start();
        }

        String url(String context) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + context;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    static void assertContains(String text, String... parts) {
        for (String part : parts) {
            assertTrue(text.contains(part), "expected \"" + part + "\" in: " + text);
        }
    }

    static List<String> none() {
        return List.of();
    }
}
