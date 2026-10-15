package br.com.habittracker;

import br.com.habittracker.config.AppConfig;
import br.com.habittracker.database.Database;
import br.com.habittracker.http.HealthHandler;
import br.com.habittracker.http.ApiHandler;
import br.com.habittracker.security.RequestSecurity;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

public final class Main {
    public static void main(String[] args) throws IOException {
        AppConfig config = AppConfig.fromEnvironment();
        // Limites do próprio HttpServer, inclusive para conexões que ainda não chegaram ao handler.
        System.setProperty("jdk.httpserver.maxConnections", "128");
        System.setProperty("sun.net.httpserver.maxReqHeaders", "50");
        System.setProperty("sun.net.httpserver.maxReqHeaderSize", "16384");
        System.setProperty("sun.net.httpserver.maxReqTime", "10000");
        System.setProperty("sun.net.httpserver.maxRspTime", "15000");
        RequestSecurity security = RequestSecurity.fromEnvironment();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", config.port()), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/api/health", new HealthHandler(new Database(config)));
        server.createContext("/api/", new ApiHandler(new Database(config), security));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(1);
            executor.close();
        }));
        server.start();
        System.out.println("API disponivel em http://localhost:" + config.port() + "/api/health");
    }
}
