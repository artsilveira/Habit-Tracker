package br.com.habittracker.database;

import br.com.habittracker.config.AppConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class Database {
    private final AppConfig config;

    public Database(AppConfig config) {
        this.config = config;
    }

    public Connection openConnection() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", config.databaseUser());
        properties.setProperty("password", config.databasePassword());
        properties.setProperty("connectTimeout", "3");
        properties.setProperty("socketTimeout", "5");
        properties.setProperty("options", "-c statement_timeout=5000 -c lock_timeout=2000 -c idle_in_transaction_session_timeout=10000");
        return DriverManager.getConnection(config.databaseUrl(), properties);
    }

    public boolean isAvailable() {
        try (Connection connection = openConnection()) {
            return connection.isValid(2);
        } catch (SQLException exception) {
            return false;
        }
    }
}
