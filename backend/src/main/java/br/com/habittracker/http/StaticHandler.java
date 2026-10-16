package br.com.habittracker.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class StaticHandler implements HttpHandler {
    private static final String POLICY = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "font-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; "
            + "base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"), Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"), Map.entry("woff2", "font/woff2"), Map.entry("txt", "text/plain; charset=utf-8"));
    private final Path root;

    public StaticHandler(Path directory) throws IOException {
        root = directory.toRealPath();
        if (!Files.isDirectory(root) || !Files.isRegularFile(root.resolve("index.html"))) {
            throw new IOException("FRONTEND_DIR precisa apontar para o build que contém index.html.");
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
            exchange.getResponseHeaders().set("Content-Security-Policy", POLICY);
            String method = exchange.getRequestMethod();
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                error(exchange, 405);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/api") || path.startsWith("/api/")) {
                error(exchange, 404);
                return;
            }
            if (path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
                error(exchange, 403);
                return;
            }
            for (String segment : path.split("/")) {
                if (segment.startsWith(".")) {
                    error(exchange, 403);
                    return;
                }
            }
            Path file = root.resolve(path.equals("/") ? "index.html" : path.substring(1)).normalize();
            if (!file.startsWith(root)) {
                error(exchange, 403);
                return;
            }
            if (!Files.isRegularFile(file)) {
                error(exchange, 404);
                return;
            }
            file = file.toRealPath();
            if (!file.startsWith(root)) {
                error(exchange, 403);
                return;
            }
            String name = file.getFileName().toString();
            int dot = name.lastIndexOf('.');
            String type = TYPES.getOrDefault(dot < 0 ? "" : name.substring(dot + 1), "application/octet-stream");
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.getResponseHeaders().set("Cache-Control", path.startsWith("/assets/")
                    ? "public, max-age=31536000, immutable" : "no-cache");
            if (method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Content-Length", Long.toString(Files.size(file)));
                exchange.sendResponseHeaders(200, -1);
            } else {
                exchange.sendResponseHeaders(200, Files.size(file));
                try (var input = Files.newInputStream(file)) {
                    input.transferTo(exchange.getResponseBody());
                }
            }
        }
    }

    private static void error(HttpExchange exchange, int status) throws IOException {
        byte[] message = "Recurso indisponível.".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        boolean head = exchange.getRequestMethod().equals("HEAD");
        exchange.sendResponseHeaders(status, head ? -1 : message.length);
        if (!head) exchange.getResponseBody().write(message);
    }
}
