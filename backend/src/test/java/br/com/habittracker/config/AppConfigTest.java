package br.com.habittracker.config;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {
    private Map<String, String> local() {
        return new HashMap<>(Map.of("DB_URL", "jdbc:postgresql://localhost/test", "DB_USER", "app", "DB_PASSWORD", "local-test-password"));
    }

    @Test void preservesLocalDefaultsAndUsesThePlatformPort() {
        Map<String, String> env = local();
        AppConfig local = AppConfig.fromEnvironment(env);
        assertEquals(8080, local.port());
        assertEquals("127.0.0.1", local.host());
        assertNull(local.frontendDirectory());
        env.put("PORT", "10000");
        env.put("APP_HOST", "0.0.0.0");
        assertEquals(10000, AppConfig.fromEnvironment(env).port());
        assertEquals("0.0.0.0", AppConfig.fromEnvironment(env).host());
        env.put("APP_PORT", "8081");
        assertEquals(8081, AppConfig.fromEnvironment(env).port());
    }

    @Test void parsesEncodedCredentialsAndEnforcesCertificateValidation() {
        AppConfig config = AppConfig.fromEnvironment(Map.of("DATABASE_URL",
                "postgresql://app:p%40ss%3Aword%2Btest@ep-example.neon.tech:5432/neondb?sslmode=require&channel_binding=require"));
        assertEquals("app", config.databaseUser());
        assertEquals("p@ss:word+test", config.databasePassword());
        assertEquals("jdbc:postgresql://ep-example.neon.tech:5432/neondb?sslmode=verify-full&sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory", config.databaseUrl());
        assertFalse(config.toString().contains("p@ss"));
        assertFalse(config.databaseUrl().contains("word"));
    }

    @Test void invalidConnectionStringsDoNotLeakCredentialsInExceptions() {
        String secret = "very-private-test-password";
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AppConfig.fromEnvironment(Map.of("DATABASE_URL", "http://app:" + secret + "@example.com/db")));
        assertFalse(error.toString().contains(secret));
        assertNull(error.getCause());
        Map<String, String> env = local();
        env.put("PORT", "0");
        assertThrows(IllegalArgumentException.class, () -> AppConfig.fromEnvironment(env));
        env.put("PORT", "bad");
        assertThrows(IllegalArgumentException.class, () -> AppConfig.fromEnvironment(env));
    }
}
