package org.opensearch.migrations.bulkload.workcoordination;

/**
 * Type-safe wrapper for SQL query strings to prevent accidental misuse.
 */
public record SQLString(String sql) {
    public SQLString {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL string cannot be null or blank");
        }
    }
}
