package br.com.habittracker.config;

public record AppConfig(int port, String databaseUrl, String databaseUser, String databasePassword) {
    public static AppConfig fromEnvironment() {
        int port = Integer.parseInt(System.getenv().getOrDefault("APP_PORT", "8080"));
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("APP_PORT deve estar entre 1 e 65535.");
        }
        return new AppConfig(port, required("DB_URL"), required("DB_USER"), required("DB_PASSWORD"));
    }

    @Override
    public String toString() {
        return "AppConfig[port=" + port + ", databaseCredentials=REDACTED]";
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Variavel obrigatoria ausente: " + name);
        }
        return value;
    }
}
