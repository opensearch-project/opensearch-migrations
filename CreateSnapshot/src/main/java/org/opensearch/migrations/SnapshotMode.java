package org.opensearch.migrations;

/**
 * Controls CreateSnapshot behavior for Solr sources.
 * <ul>
 *   <li>{@code CREATE} — standard snapshot creation (existing behavior)</li>
 *   <li>{@code IMPORT} — import workflow (config retrieval + external snapshot import)</li>
 * </ul>
 */
public enum SnapshotMode {
    CREATE,
    IMPORT;

    public static SnapshotMode fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return CREATE;
        }
        return valueOf(value.trim().toUpperCase());
    }
}
