package org.opensearch.migrations.bulkload.workcoordination;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class PostgresClientTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("test")
        .withUsername("test")
        .withPassword("test");

    private PostgresClient client;

    @BeforeEach
    void setUp() {
        client = new PostgresClient(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword()
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void testBasicQueryExecution() throws Exception {
        var result = client.executeQuery(
            "SELECT 1 as value",
            rs -> {
                if (rs.next()) {
                    return rs.getInt("value");
                }
                return null;
            }
        );
        
        assertEquals(1, result);
    }

    @Test
    void testQueryWithParameters() throws Exception {
        client.executeUpdate(
            "CREATE TEMP TABLE test_table (id INT, name VARCHAR(50))"
        );
        
        client.executeUpdate(
            "INSERT INTO test_table (id, name) VALUES (?, ?)",
            1, "test-name"
        );
        
        var result = client.executeQuery(
            "SELECT name FROM test_table WHERE id = ?",
            rs -> {
                if (rs.next()) {
                    return rs.getString("name");
                }
                return null;
            },
            1
        );
        
        assertEquals("test-name", result);
    }

    @Test
    void testTransactionCommit() throws Exception {
        client.executeUpdate(
            "CREATE TEMP TABLE test_table (id INT, value VARCHAR(50))"
        );
        
        var result = client.executeInTransaction(conn -> {
            try (var stmt = conn.prepareStatement("INSERT INTO test_table (id, value) VALUES (?, ?)")) {
                stmt.setInt(1, 1);
                stmt.setString(2, "value1");
                stmt.executeUpdate();
            }
            
            try (var stmt = conn.prepareStatement("INSERT INTO test_table (id, value) VALUES (?, ?)")) {
                stmt.setInt(1, 2);
                stmt.setString(2, "value2");
                stmt.executeUpdate();
            }
            
            return "success";
        });
        
        assertEquals("success", result);
        
        var count = client.executeQuery(
            "SELECT COUNT(*) as cnt FROM test_table",
            rs -> {
                if (rs.next()) {
                    return rs.getInt("cnt");
                }
                return 0;
            }
        );
        
        assertEquals(2, count);
    }

    @Test
    void testTransactionRollback() throws Exception {
        client.executeUpdate(
            "CREATE TEMP TABLE test_table (id INT PRIMARY KEY, value VARCHAR(50))"
        );
        
        assertThrows(SQLException.class, () -> {
            client.executeInTransaction(conn -> {
                try (var stmt = conn.prepareStatement("INSERT INTO test_table (id, value) VALUES (?, ?)")) {
                    stmt.setInt(1, 1);
                    stmt.setString(2, "value1");
                    stmt.executeUpdate();
                }
                
                try (var stmt = conn.prepareStatement("INSERT INTO test_table (id, value) VALUES (?, ?)")) {
                    stmt.setInt(1, 1);
                    stmt.setString(2, "duplicate");
                    stmt.executeUpdate();
                }
                
                return null;
            });
        });
        
        var count = client.executeQuery(
            "SELECT COUNT(*) as cnt FROM test_table",
            rs -> {
                if (rs.next()) {
                    return rs.getInt("cnt");
                }
                return 0;
            }
        );
        
        assertEquals(0, count, "Transaction should have rolled back");
    }

    @Test
    void testConcurrentAccess() throws Exception {
        client.executeUpdate(
            "CREATE TEMP TABLE test_table (id INT)"
        );
        
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        List<Future<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < threadCount; i++) {
            final int id = i;
            futures.add(executor.submit(() -> {
                client.executeUpdate(
                    "INSERT INTO test_table (id) VALUES (?)",
                    id
                );
                return null;
            }));
        }
        
        for (var future : futures) {
            future.get();
        }
        
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        
        var count = client.executeQuery(
            "SELECT COUNT(*) as cnt FROM test_table",
            rs -> rs.next() ? rs.getInt("cnt") : 0
        );
        
        assertEquals(threadCount, count);
    }

    @Test
    void testNullParameterHandling() throws Exception {
        client.executeUpdate(
            "CREATE TEMP TABLE test_table (id INT, nullable_value VARCHAR(50))"
        );
        
        client.executeUpdate(
            "INSERT INTO test_table (id, nullable_value) VALUES (?, ?)",
            1, null
        );
        
        var result = client.executeQuery(
            "SELECT nullable_value FROM test_table WHERE id = ?",
            rs -> {
                if (rs.next()) {
                    return rs.getString("nullable_value");
                }
                return "not-found";
            },
            1
        );
        
        assertNull(result);
    }

    @Test
    void testMultipleResultSetRows() throws Exception {
        client.executeUpdate(
            "CREATE TEMP TABLE test_table (id INT, value VARCHAR(50))"
        );
        
        for (int i = 1; i <= 3; i++) {
            client.executeUpdate(
                "INSERT INTO test_table (id, value) VALUES (?, ?)",
                i, "value-" + i
            );
        }
        
        var results = client.executeQuery(
            "SELECT value FROM test_table ORDER BY id",
            rs -> {
                var list = new ArrayList<String>();
                while (rs.next()) {
                    list.add(rs.getString("value"));
                }
                return list;
            }
        );
        
        assertEquals(3, results.size());
        assertEquals("value-1", results.get(0));
        assertEquals("value-3", results.get(2));
    }
}
