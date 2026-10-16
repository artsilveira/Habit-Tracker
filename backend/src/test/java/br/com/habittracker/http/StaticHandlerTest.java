package br.com.habittracker.http;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StaticHandlerTest {
    @TempDir Path directory;
    private HttpServer server;
    private String base;
    private Path root;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @BeforeEach void start() throws IOException {
        root = Files.createDirectory(directory.resolve("public"));
        Files.writeString(root.resolve("index.html"), "<!doctype html><h1>Habit Tracker</h1>");
        Files.createDirectory(root.resolve("assets"));
        Files.writeString(root.resolve("assets/app-test1234.js"), "console.log('ready');");
        Files.writeString(root.resolve(".env"), "NOT_PUBLIC=true");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new StaticHandler(root));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stop() { server.stop(0); }

    private HttpResponse<String> request(String path, String method) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(3))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void servesHtmlAndAssetsWithSecurityAndCacheHeaders() throws Exception {
        HttpResponse<String> html = request("/", "GET");
        assertEquals(200, html.statusCode());
        assertTrue(html.body().contains("Habit Tracker"));
        assertEquals("no-cache", html.headers().firstValue("cache-control").orElseThrow());
        assertTrue(html.headers().firstValue("content-security-policy").orElseThrow().contains("frame-ancestors 'none'"));
        HttpResponse<String> js = request("/assets/app-test1234.js", "GET");
        assertEquals(200, js.statusCode());
        assertTrue(js.headers().firstValue("content-type").orElseThrow().startsWith("text/javascript"));
        assertTrue(js.headers().firstValue("cache-control").orElseThrow().contains("immutable"));
        assertEquals("nosniff", js.headers().firstValue("x-content-type-options").orElseThrow());
        HttpResponse<String> head = request("/", "HEAD");
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());
    }

    @Test void rejectsTraversalHiddenFilesDirectoryListingAndUnknownApiPaths() throws Exception {
        assertEquals(403, request("/.env", "GET").statusCode());
        assertEquals(403, request("/%2e%2e/secret.txt", "GET").statusCode());
        assertEquals(403, request("/assets%5c..%5c.env", "GET").statusCode());
        assertEquals(404, request("/assets/", "GET").statusCode());
        assertEquals(404, request("/missing.js", "GET").statusCode());
        assertEquals(404, request("/api", "GET").statusCode());
        assertEquals(404, request("/api/not-a-route", "GET").statusCode());
        assertEquals(405, request("/", "POST").statusCode());
    }

    @Test void neverServesSymlinksOutsideTheBuildDirectory() throws Exception {
        Path privateFile = Files.writeString(directory.resolve("private.txt"), "NOT_PUBLIC");
        try {
            Files.createSymbolicLink(root.resolve("leak.txt"), privateFile);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            Assumptions.abort("O sistema não permite criar symlinks neste teste.");
        }
        assertEquals(403, request("/leak.txt", "GET").statusCode());
    }
}
