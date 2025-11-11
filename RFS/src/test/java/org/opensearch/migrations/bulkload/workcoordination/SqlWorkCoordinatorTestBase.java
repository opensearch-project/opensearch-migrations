package org.opensearch.migrations.bulkload.workcoordination;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public abstract class SqlWorkCoordinatorTestBase {

    @Container
    protected static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("test")
        .withUsername("test")
        .withPassword("test");

    protected static final String TABLE_NAME = "work_items";
    protected List<SqlWorkCoordinator> coordinators;
    protected DatabaseClient dbClient;
    protected TestClock testClock;

    @BeforeEach
    void setUp() throws Exception {
        dbClient = new SqlClient(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword()
        );
        
        testClock = new TestClock(Instant.now());
        coordinators = new ArrayList<>();
        var setupCoordinator = createCoordinator("setup-worker");
        setupCoordinator.setup(() -> null);
        setupCoordinator.close();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (var coordinator : coordinators) {
            coordinator.close();
        }
        coordinators.clear();
        resetDatabase();
    }

    protected SqlWorkCoordinator createCoordinator(String workerId) {
        return createCoordinator(workerId, testClock);
    }

    protected SqlWorkCoordinator createCoordinator(String workerId, Clock clock) {
        var client = new SqlClient(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword()
        );
        var coordinator = new SqlWorkCoordinator(
            client,
            TABLE_NAME,
            workerId,
            clock,
            w -> {}
        );
        coordinators.add(coordinator);
        return coordinator;
    }

    private void resetDatabase() throws Exception {
        if (dbClient != null && dbClient instanceof SqlClient) {
            try {
                ((SqlClient) dbClient).executeUpdate("DROP TABLE IF EXISTS " + TABLE_NAME + " CASCADE");
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }
}
