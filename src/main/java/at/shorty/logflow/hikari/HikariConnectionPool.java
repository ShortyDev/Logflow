package at.shorty.logflow.hikari;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;

@Slf4j
public class HikariConnectionPool {

    private final HikariDataSource dataSource;

    public HikariConnectionPool(String jdbcUrl, String username, String password) {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
    }

    public Connection getConnection() {
        try {
            return dataSource.getConnection();
        } catch (Exception exception) {
            log.error("Failed to get connection from pool", exception);
            return null;
        }
    }

    public void close() {
        dataSource.close();
    }
}
