package br.com.habittracker.http;

import br.com.habittracker.database.Database;
import br.com.habittracker.security.RequestSecurity;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class HealthHandler implements HttpHandler {
    private final Database database;
    private long lastCheck;
    private boolean available;

    public HealthHandler(Database database) {
        this.database = database;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestURI().getPath().equals("/api/health")) {
                respond(exchange, 404, "{\"code\":\"NOT_FOUND\"}");
                return;
            }
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                respond(exchange, 405, "{\"code\":\"METHOD_NOT_ALLOWED\"}");
                return;
            }
            boolean available = databaseAvailable();
            respond(exchange, available ? 200 : 503,
                    available ? "{\"status\":\"UP\",\"database\":\"UP\"}"
                              : "{\"status\":\"DOWN\",\"database\":\"DOWN\"}");
        }
    }

    private synchronized boolean databaseAvailable() {
        long now = System.nanoTime();
        if (lastCheck == 0 || now - lastCheck > 2_000_000_000L) {
            available = database.isAvailable();
            lastCheck = now;
        }
        return available;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        RequestSecurity.headers(exchange);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
