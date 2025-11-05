package org.opensearch.migrations.bulkload.workcoordination;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

class SqlQueryBuilder {
    private static final String TABLE_NAME_PATTERN = "^[a-zA-Z_][a-zA-Z0-9_]*$";
    private final String tableName;

    SqlQueryBuilder(String tableName) {
        if (!tableName.matches(TABLE_NAME_PATTERN)) {
            throw new IllegalArgumentException("Invalid table name: " + tableName);
        }
        this.tableName = tableName;
    }

    boolean insertUnassignedWorkItem(Connection conn, String workItemId, String creatorId) throws SQLException {
        var sql = new SQLString("INSERT INTO " + tableName + 
            " (work_item_id, expiration, creator_id) VALUES (?, 0, ?) ON CONFLICT (work_item_id) DO NOTHING");
        try (var stmt = conn.prepareStatement(sql.sql())) {
            stmt.setString(1, workItemId);
            stmt.setString(2, creatorId);
            return stmt.executeUpdate() > 0;
        }
    }

    LeaseResult upsertLease(Connection conn, String workItemId, long expirationSeconds, String leaseHolderId, 
                            String creatorId, long nowSeconds) throws SQLException {
        var sql = new SQLString("INSERT INTO " + tableName + 
            " (work_item_id, expiration, lease_holder_id, creator_id, next_acquisition_lease_exponent) " +
            "VALUES (?, ?, ?, ?, 0) " +
            "ON CONFLICT (work_item_id) DO UPDATE SET " +
            "  expiration = CASE " +
            "    WHEN " + tableName + ".completed_at IS NULL " +
            "      AND " + tableName + ".expiration < ? " +
            "      AND " + tableName + ".expiration < EXCLUDED.expiration " +
            "    THEN EXCLUDED.expiration " +
            "    ELSE " + tableName + ".expiration " +
            "  END, " +
            "  lease_holder_id = CASE " +
            "    WHEN " + tableName + ".expiration < ? " +
            "    THEN EXCLUDED.lease_holder_id " +
            "    ELSE " + tableName + ".lease_holder_id " +
            "  END, " +
            "  next_acquisition_lease_exponent = CASE " +
            "    WHEN " + tableName + ".expiration < ? " +
            "    THEN " + tableName + ".next_acquisition_lease_exponent + 1 " +
            "    ELSE " + tableName + ".next_acquisition_lease_exponent " +
            "  END, " +
            "  updated_at = CURRENT_TIMESTAMP " +
            "RETURNING completed_at, expiration, lease_holder_id");
        try (var stmt = conn.prepareStatement(sql.sql())) {
            stmt.setString(1, workItemId);
            stmt.setLong(2, expirationSeconds);
            stmt.setString(3, leaseHolderId);
            stmt.setString(4, creatorId);
            stmt.setLong(5, nowSeconds);
            stmt.setLong(6, nowSeconds);
            stmt.setLong(7, nowSeconds);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("No result from upsert");
                }
                return new LeaseResult(
                    rs.getObject("completed_at", Long.class),
                    rs.getLong("expiration"),
                    rs.getString("lease_holder_id")
                );
            }
        }
    }

    Optional<AvailableWorkItem> selectAvailableWorkItem(Connection conn, long nowSeconds) throws SQLException {
        var sql = new SQLString("SELECT work_item_id, next_acquisition_lease_exponent, successor_items " +
            "FROM " + tableName + " " +
            "WHERE completed_at IS NULL AND expiration < ? " +
            "ORDER BY RANDOM() LIMIT 1 FOR UPDATE SKIP LOCKED");
        try (var stmt = conn.prepareStatement(sql.sql())) {
            stmt.setLong(1, nowSeconds);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new AvailableWorkItem(
                    rs.getString("work_item_id"),
                    rs.getInt("next_acquisition_lease_exponent"),
                    rs.getString("successor_items")
                ));
            }
        }
    }

    boolean updateLease(Connection conn, long newExpiration, String leaseHolderId, String workItemId) throws SQLException {
        var sql = new SQLString("UPDATE " + tableName + " " +
            "SET expiration = ?, lease_holder_id = ?, " +
            "    next_acquisition_lease_exponent = next_acquisition_lease_exponent + 1, " +
            "    updated_at = CURRENT_TIMESTAMP " +
            "WHERE work_item_id = ?");
        try (var stmt = conn.prepareStatement(sql.sql())) {
            stmt.setLong(1, newExpiration);
            stmt.setString(2, leaseHolderId);
            stmt.setString(3, workItemId);
            return stmt.executeUpdate() > 0;
        }
    }

    boolean completeWorkItem(Connection conn, long completedAt, String workItemId, String leaseHolderId) throws SQLException {
        var sql = new SQLString("UPDATE " + tableName + " " +
            "SET completed_at = ?, updated_at = CURRENT_TIMESTAMP " +
            "WHERE work_item_id = ? AND lease_holder_id = ? AND completed_at IS NULL");
        try (var stmt = conn.prepareStatement(sql.sql())) {
            stmt.setLong(1, completedAt);
            stmt.setString(2, workItemId);
            stmt.setString(3, leaseHolderId);
            return stmt.executeUpdate() > 0;
        }
    }

    void updateSuccessors(Connection conn, List<String> successorWorkItemIds, String workItemId, String leaseHolderId) throws SQLException {
        var sql = new SQLString("UPDATE " + tableName + " " +
            "SET successor_items = ?, updated_at = CURRENT_TIMESTAMP " +
            "WHERE work_item_id = ? AND lease_holder_id = ?");
        try (var stmt = conn.prepareStatement(sql.sql())) {
            stmt.setString(1, String.join(",", successorWorkItemIds));
            stmt.setString(2, workItemId);
            stmt.setString(3, leaseHolderId);
            stmt.executeUpdate();
        }
    }

    void insertSuccessors(Connection conn, List<String> successorIds, String creatorId, int leaseExponent) throws SQLException {
        var sql = new SQLString("INSERT INTO " + tableName + 
            " (work_item_id, expiration, creator_id, next_acquisition_lease_exponent) " +
            "VALUES (?, 0, ?, ?) ON CONFLICT (work_item_id) DO NOTHING");
        try (var stmt = conn.prepareStatement(sql.sql())) {
            for (String successorId : successorIds) {
                stmt.setString(1, successorId);
                stmt.setString(2, creatorId);
                stmt.setInt(3, leaseExponent);
                stmt.addBatch();
            }
            stmt.executeBatch();
        }
    }

    int countIncomplete(Connection conn) throws SQLException {
        var sql = new SQLString("SELECT COUNT(*) FROM " + tableName + " WHERE completed_at IS NULL");
        try (var stmt = conn.prepareStatement(sql.sql())) {
            try (var rs = stmt.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
