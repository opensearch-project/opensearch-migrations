package org.opensearch.migrations.bulkload.workcoordination;

import java.sql.ResultSet;
import java.sql.SQLException;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PostgresClient implements DatabaseClient {
    private final HikariDataSource dataSource;
    
    public PostgresClient(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, false);
    }
    
    public PostgresClient(String jdbcUrl, String username, String password, boolean enableSsl) {
        log.debug("Initializing PostgresClient with jdbcUrl={}, username={}, ssl={}", jdbcUrl, username, enableSsl);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(100);
        config.setMinimumIdle(10);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        
        if (enableSsl) {
            config.addDataSourceProperty("ssl", "true");
            config.addDataSourceProperty("sslmode", "require");
        }
        
        this.dataSource = new HikariDataSource(config);
        log.debug("PostgresClient initialized successfully");
    }
    
    @Override
    public <T> T executeInTransaction(TransactionFunction<T> operation) throws SQLException {
        log.debug("Executing transaction");
        return retryOnce(() -> {
            try (var conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    T result = operation.apply(conn);
                    conn.commit();
                    log.debug("Transaction committed successfully");
                    return result;
                } catch (SQLException e) {
                    log.debug("Transaction failed, rolling back: {}", e.getMessage());
                    conn.rollback();
                    throw e;
                }
            }
        });
    }
    
    private <T> T retryOnce(SqlOperation<T> operation) throws SQLException {
        try {
            return operation.execute();
        } catch (SQLException e) {
            if (isTransientError(e)) {
                log.debug("Transient error detected, retrying once: {}", e.getMessage());
                return operation.execute();
            }
            throw e;
        }
    }
    
    private boolean isTransientError(SQLException e) {
        String sqlState = e.getSQLState();
        return sqlState != null && (
            sqlState.startsWith("08") ||  // Connection exception
            sqlState.equals("40001") ||   // Serialization failure
            sqlState.equals("40P01")      // Deadlock detected
        );
    }
    
    @FunctionalInterface
    private interface SqlOperation<T> {
        T execute() throws SQLException;
    }
    
    public <T> T executeQuery(String sql, ResultSetMapper<T> mapper, Object... params) throws SQLException {
        return executeInTransaction(conn -> {
            try (var stmt = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    stmt.setObject(i + 1, params[i]);
                }
                try (var rs = stmt.executeQuery()) {
                    return mapper.map(rs);
                }
            }
        });
    }
    
    public void executeUpdate(String sql, Object... params) throws SQLException {
        executeInTransaction(conn -> {
            try (var stmt = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    stmt.setObject(i + 1, params[i]);
                }
                stmt.executeUpdate();
            }
            return null;
        });
    }
    
    @FunctionalInterface
    public interface ResultSetMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }
    
    @Override
    public void close() {
        if (dataSource != null) {
            log.debug("Closing PostgresClient connection pool");
            dataSource.close();
        }
    }
}
