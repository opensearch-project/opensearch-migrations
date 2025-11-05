package org.opensearch.migrations.bulkload.workcoordination;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.opensearch.migrations.bulkload.tracing.IWorkCoordinationContexts;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PostgresWorkCoordinator implements IWorkCoordinator {
    private final DatabaseClient dbClient;
    private final String tableName;
    private final String workerId;
    private final Clock clock;
    private final Consumer<WorkItemAndDuration> workItemConsumer;
    private final SqlQueryBuilder sqlBuilder;

    public PostgresWorkCoordinator(
        DatabaseClient dbClient,
        String tableName,
        String workerId,
        Clock clock,
        Consumer<WorkItemAndDuration> workItemConsumer
    ) {
        this.dbClient = dbClient;
        this.tableName = tableName;
        this.workerId = workerId;
        this.clock = clock;
        this.workItemConsumer = workItemConsumer;
        this.sqlBuilder = new SqlQueryBuilder(tableName);
    }

    @Override
    public Clock getClock() {
        return clock;
    }

    @Override
    public void setup(Supplier<IWorkCoordinationContexts.IInitializeCoordinatorStateContext> contextSupplier)
        throws IOException {
        try (var ctx = contextSupplier.get()) {
            var is = getClass().getResourceAsStream("/db/work_coordination_schema.sql");
            if (is == null) {
                throw new IOException("Schema file not found: /db/work_coordination_schema.sql");
            }
            
            try (var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                var schema = reader.lines().collect(Collectors.joining("\n"));
                
                dbClient.executeInTransaction(conn -> {
                    try (var stmt = conn.createStatement()) {
                        stmt.execute(schema.replace("work_items", tableName));
                    }
                    return null;
                });
                log.info("Worker {} initialized schema for table: {}", workerId, tableName);
            }
        } catch (SQLException e) {
            throw new WorkCoordinationException("setup", "N/A", "Failed to initialize schema", e);
        }
    }

    @Override
    public boolean createUnassignedWorkItem(
        WorkItem workItem,
        Supplier<IWorkCoordinationContexts.ICreateUnassignedWorkItemContext> contextSupplier
    ) throws IOException {
        var workItemId = workItem.toString();
        try (var ctx = contextSupplier.get()) {
            log.debug("Worker {} creating unassigned work item {}", workerId, workItemId);
            
            var created = dbClient.executeInTransaction(conn -> 
                sqlBuilder.insertUnassignedWorkItem(conn, workItemId, workerId)
            );
            
            if (created) {
                log.info("Worker {} created unassigned work item {}", workerId, workItemId);
            } else {
                log.debug("Work item {} already exists", workItemId);
            }
            return created;
        } catch (SQLException e) {
            throw new WorkCoordinationException("createUnassignedWorkItem", workItemId, "Database error", e);
        }
    }

    @Override
    @NonNull
    public WorkAcquisitionOutcome createOrUpdateLeaseForWorkItem(
        WorkItem workItem,
        Duration leaseDuration,
        Supplier<IWorkCoordinationContexts.IAcquireSpecificWorkContext> contextSupplier
    ) throws IOException, InterruptedException {
        var workItemId = workItem.toString();
        try (var ctx = contextSupplier.get()) {
            log.debug("Worker {} attempting to acquire specific work item {}", workerId, workItemId);
            
            var nowSeconds = clock.instant().getEpochSecond();
            var expirationSeconds = nowSeconds + leaseDuration.toSeconds();
            
            return dbClient.executeInTransaction(conn -> {
                var result = sqlBuilder.upsertLease(conn, workItemId, expirationSeconds, workerId, workerId, nowSeconds);
                
                if (result.getCompletedAt() != null) {
                    log.debug("Work item {} already completed", workItemId);
                    return new AlreadyCompleted();
                } else if (result.getLeaseHolderId() != null && result.getLeaseHolderId().equals(workerId) 
                           && result.getExpiration() > nowSeconds) {
                    log.info("Worker {} acquired work item {} with lease until {}", 
                        workerId, workItemId, Instant.ofEpochSecond(result.getExpiration()));
                    return new WorkItemAndDuration(
                        Instant.ofEpochSecond(result.getExpiration()),
                        workItem
                    );
                } else {
                    log.warn("Worker {} failed to acquire work item {} - held by {}", 
                        workerId, workItemId, result.getLeaseHolderId());
                    throw new LeaseLockHeldElsewhereException();
                }
            });
        } catch (SQLException e) {
            throw new WorkCoordinationException("createOrUpdateLeaseForWorkItem", workItemId, 
                "Database error", e);
        }
    }

    @Override
    public WorkAcquisitionOutcome acquireNextWorkItem(
        Duration leaseDuration,
        Supplier<IWorkCoordinationContexts.IAcquireNextWorkItemContext> contextSupplier
    ) throws IOException, InterruptedException {
        try (var ctx = contextSupplier.get()) {
            long nowSeconds = clock.instant().getEpochSecond();
            
            return dbClient.executeInTransaction(conn -> {
                var availableWork = findAvailableWork(conn, nowSeconds, ctx);
                if (availableWork == null) {
                    return new NoAvailableWorkToBeDone();
                }
                
                if (hasSuccessors(availableWork)) {
                    try {
                        return handleSuccessors(availableWork, leaseDuration, contextSupplier, ctx);
                    } catch (IOException | InterruptedException e) {
                        throw new SQLException("Failed to handle successor items", e);
                    }
                }
                
                return acquireWork(conn, availableWork, leaseDuration, nowSeconds, ctx);
            });
        } catch (SQLException e) {
            throw new WorkCoordinationException("acquireNextWorkItem", null, "Database error", e);
        }
    }

    private AvailableWorkItem findAvailableWork(
        java.sql.Connection conn,
        long nowSeconds,
        IWorkCoordinationContexts.IAcquireNextWorkItemContext ctx
    ) throws SQLException {
        var availableWork = sqlBuilder.selectAvailableWorkItem(conn, nowSeconds);
        if (availableWork.isEmpty()) {
            if (ctx != null) {
                ctx.recordNothingAvailable();
            }
            log.debug("Worker {} found no available work", workerId);
            return null;
        }
        return availableWork.get();
    }

    private boolean hasSuccessors(AvailableWorkItem work) {
        var successorItems = work.getSuccessorItems();
        return successorItems != null && !successorItems.isEmpty();
    }

    private WorkAcquisitionOutcome handleSuccessors(
        AvailableWorkItem work,
        Duration leaseDuration,
        Supplier<IWorkCoordinationContexts.IAcquireNextWorkItemContext> contextSupplier,
        IWorkCoordinationContexts.IAcquireNextWorkItemContext ctx
    ) throws SQLException, IOException, InterruptedException {
        var workItemId = work.getWorkItemId();
        log.debug("Work item {} has successors, creating and retrying", workItemId);
        var successorList = List.of(work.getSuccessorItems().split(","));
        var successorWorkItems = successorList.stream()
            .map(WorkItem::fromString)
            .collect(Collectors.toList());
        createSuccessorWorkItemsAndMarkComplete(WorkItem.fromString(workItemId), successorWorkItems, 0, 
            ctx::getCreateSuccessorWorkItemsContext);
        return acquireNextWorkItem(leaseDuration, contextSupplier);
    }

    private WorkItemAndDuration acquireWork(
        java.sql.Connection conn,
        AvailableWorkItem work,
        Duration leaseDuration,
        long nowSeconds,
        IWorkCoordinationContexts.IAcquireNextWorkItemContext ctx
    ) throws SQLException {
        var workItemId = work.getWorkItemId();
        var leaseExponent = work.getNextAcquisitionLeaseExponent();
        var adjustedLeaseDuration = LeaseCalculator.calculateLeaseDuration(leaseDuration, leaseExponent);
        var newExpiration = nowSeconds + adjustedLeaseDuration;
        
        log.debug("Worker {} acquiring work item {} with lease exponent {} (duration: {}s)", 
            workerId, workItemId, leaseExponent, adjustedLeaseDuration);
        
        sqlBuilder.updateLease(conn, newExpiration, workerId, workItemId);
        
        if (ctx != null) {
            ctx.recordAssigned();
        }
        var workItemAndDuration = new WorkItemAndDuration(
            Instant.ofEpochSecond(newExpiration),
            WorkItem.fromString(workItemId)
        );
        workItemConsumer.accept(workItemAndDuration);
        log.info("Worker {} acquired work item {} with lease until {}", 
            workerId, workItemId, Instant.ofEpochSecond(newExpiration));
        return workItemAndDuration;
    }

    @Override
    public void completeWorkItem(
        WorkItem workItem,
        Supplier<IWorkCoordinationContexts.ICompleteWorkItemContext> contextSupplier
    ) throws IOException {
        var workItemId = workItem.toString();
        try (var ctx = contextSupplier.get()) {
            log.debug("Worker {} completing work item {}", workerId, workItemId);
            
            var nowSeconds = clock.instant().getEpochSecond();
            var success = dbClient.executeInTransaction(conn -> 
                sqlBuilder.completeWorkItem(conn, nowSeconds, workItemId, workerId)
            );
            
            if (!success) {
                throw new WorkCoordinationException("completeWorkItem", workItemId, 
                    "Not owned by this worker or already completed");
            }
            log.info("Worker {} completed work item {}", workerId, workItemId);
        } catch (SQLException e) {
            throw new WorkCoordinationException("completeWorkItem", workItemId, "Database error", e);
        }
    }

    @Override
    public void createSuccessorWorkItemsAndMarkComplete(
        WorkItem workItem,
        List<WorkItem> successorWorkItems,
        int initialNextAcquisitionLeaseExponent,
        Supplier<IWorkCoordinationContexts.ICreateSuccessorWorkItemsContext> contextSupplier
    ) throws IOException, InterruptedException {
        var workItemId = workItem.toString();
        var successorWorkItemIds = successorWorkItems.stream()
            .map(WorkItem::toString)
            .collect(Collectors.toList());
        
        try (var ctx = contextSupplier.get()) {
            log.debug("Worker {} creating {} successors for work item {}", 
                workerId, successorWorkItemIds.size(), workItemId);
            
            dbClient.executeInTransaction(conn -> {
                sqlBuilder.updateSuccessors(conn, successorWorkItemIds, workItemId, workerId);
                sqlBuilder.insertSuccessors(conn, successorWorkItemIds, workerId, initialNextAcquisitionLeaseExponent);
                
                var nowSeconds = clock.instant().getEpochSecond();
                var success = sqlBuilder.completeWorkItem(conn, nowSeconds, workItemId, workerId);
                if (!success) {
                    throw new SQLException("Failed to mark work item as complete");
                }
                
                log.info("Worker {} created {} successors and completed work item {}", 
                    workerId, successorWorkItemIds.size(), workItemId);
                return null;
            });
        } catch (SQLException e) {
            throw new WorkCoordinationException("createSuccessorWorkItemsAndMarkComplete", workItemId, 
                "Database error", e);
        }
    }

    @Override
    public int numWorkItemsNotYetComplete(Supplier<IWorkCoordinationContexts.IPendingWorkItemsContext> contextSupplier)
        throws IOException {
        try (var ctx = contextSupplier.get()) {
            var count = dbClient.executeInTransaction(conn -> sqlBuilder.countIncomplete(conn));
            log.debug("Worker {} found {} incomplete work items", workerId, count);
            return count;
        } catch (SQLException e) {
            throw new WorkCoordinationException("numWorkItemsNotYetComplete", null, "Database error", e);
        }
    }

    @Override
    public boolean workItemsNotYetComplete(Supplier<IWorkCoordinationContexts.IPendingWorkItemsContext> contextSupplier)
        throws IOException {
        return numWorkItemsNotYetComplete(contextSupplier) > 0;
    }

    @Override
    public void close() throws Exception {
        dbClient.close();
    }
}
