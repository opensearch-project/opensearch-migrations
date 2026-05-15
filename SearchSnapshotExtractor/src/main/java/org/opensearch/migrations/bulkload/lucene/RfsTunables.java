package org.opensearch.migrations.bulkload.lucene;

/**
 * Central catalog of JVM system-property and environment-variable tunables for the
 * RFS (Reindex-from-Snapshot) sourceless reconstruction pipeline. All tunables listed
 * here can be set via {@code -D<property>} in {@code JDK_JAVA_OPTIONS} or (where noted)
 * via an environment variable for container-level overrides.
 */
public final class RfsTunables {

    private RfsTunables() {}

    // --- Reader parallelism ---

    /**
     * Per-segment reader parallelism — number of independent LeafReader views that
     * process a segment's docId space concurrently via round-robin striping.
     *
     * <p>System property: {@code -Drfs.reader.parallelism=<int>}
     * <p>Env var fallback: {@code RFS_READER_PARALLELISM}
     * <p>Default: availableProcessors - 1 (min 1).
     *
     * <p>Higher values saturate CPU on reconstruction-heavy segments (sourceless fields
     * with doc_values/points/terms recovery). Diminishing returns past the point where
     * the bulk-loader write pipeline or the SegmentTermIndex monitor become the bottleneck.
     */
    public static final String READER_PARALLELISM_PROP = "rfs.reader.parallelism";
    public static final String READER_PARALLELISM_ENV = "RFS_READER_PARALLELISM";
}
