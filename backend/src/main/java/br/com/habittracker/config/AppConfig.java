package br.com.habittracker.config;

import java.net.URI;
import java.nio.file.Path;
import java.util.Map;

public record AppConfig(int port, String host, String databaseUrl, String databaseUser,
                        String databasePassword, Path frontendDirectory) {
    public static AppConfig fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    public static AppConfig fromEnvironment(Map<String, String> environment) {
        int port;
        try {
            port = Integer.parseInt(environment.getOrDefault("APP_PORT", environment.getOrDefault("PORT", "8080")));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("APP_PORT ou PORT deve ser uma porta numérica.");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("A porta deve estar entre 1 e 65535.");
        String host = environment.getOrDefault("APP_HOST", "127.0.0.1");
        if (!host.equals("127.0.0.1") && !host.equals("0.0.0.0")) {
            throw new IllegalArgumentException("APP_HOST deve ser 127.0.0.1 ou 0.0.0.0.");
        }
        Path frontend = environment.containsKey("FRONTEND_DIR")
                ? Path.of(required(environment, "FRONTEND_DIR")) : null;
        if (environment.containsKey("DATABASE_URL")) {
            return fromConnectionString(port, host, frontend, required(environment, "DATABASE_URL"));
        }
        return new AppConfig(port, host, required(environment, "DB_URL"),
                required(environment, "DB_USER"), required(environment, "DB_PASSWORD"), frontend);
    }

    private static AppConfig fromConnectionString(int port, String host, Path frontend, String value) {
        // Neon fornece uma URI PostgreSQL; o driver Java precisa de uma URL JDBC.
        // Não incluir a URI original em exceções: ela contém a senha.
        try {
            URI uri = URI.create(value);
            if (!("postgresql".equals(uri.getScheme()) || "postgres".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() == null || uri.getFragment() != null
                    || uri.getRawPath() == null || !uri.getRawPath().matches("/[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException();
            }
            String credentials = uri.getUserInfo();
            int separator = credentials.indexOf(':');
            if (separator < 1 || separator == credentials.length() - 1) throw new IllegalArgumentException();
            if (uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            String databaseHost = uri.getHost();
            if (databaseHost.contains(":") && !databaseHost.startsWith("[")) databaseHost = "[" + databaseHost + "]";
            String jdbc = "jdbc:postgresql://" + databaseHost + (uri.getPort() == -1 ? "" : ":" + uri.getPort())
                    + uri.getRawPath()
                    + "?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory";
            // A configuração de TLS é da aplicação, não de parâmetros opcionais copiados da URI.
            return new AppConfig(port, host, jdbc, credentials.substring(0, separator),
                    credentials.substring(separator + 1), frontend);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("DATABASE_URL inválida. Use a URI PostgreSQL fornecida no painel de conexão, com usuário, senha e banco.");
        }
    }

    @Override
    public String toString() {
        return "AppConfig[port=" + port + ", databaseCredentials=REDACTED]";
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Variavel obrigatoria ausente: " + name);
        }
        return value;
    }
}
