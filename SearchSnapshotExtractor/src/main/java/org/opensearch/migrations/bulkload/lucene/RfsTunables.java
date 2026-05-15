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

    // --- External merge-sort buffer ---

    /**
     * In-memory sort-buffer budget (bytes) for the external merge sort used by the
     * sidecar-based term index (tier-3 text field recovery).
     *
     * <p>System property: {@code -Drfs.reconstruction.sortBufferBytes=<long>}
     * <p>Default: 256 MiB ({@code 268435456}).
     *
     * <p>Smaller buffer → more spill runs → more merge passes → more disk I/O but bounded
     * heap. Tune down on workers with less than 4 GiB heap; tune up on large workers to
     * reduce merge passes for very high-cardinality text fields.
     */
    public static final String SORT_BUFFER_BYTES_PROP = "rfs.reconstruction.sortBufferBytes";
    public static final long DEFAULT_SORT_BUFFER_BYTES = 256L * 1024 * 1024;

    // --- Streaming postings toggle ---

    /**
     * Enables/disables the streaming-postings path for tier-3 text field recovery.
     * When {@code true} (default), text recovery walks posting lists via a min-heap of
     * PostingsEnum cursors in a single pass, skipping the OfflineSorter-backed sidecar
     * build entirely. Set to {@code false} to fall back to the sidecar build path.
     *
     * <p>System property: {@code -Drfs.sourceless.streamingPostings=true|false}
     * <p>Default: {@code true}.
     *
     * <p>The streaming path uses less disk I/O and avoids temp-file creation but holds
     * more heap during reconstruction (one PostingsEnum per unique term in the segment).
     * Disable on segments with extremely high term cardinality if OOM occurs.
     */
    public static final String STREAMING_POSTINGS_PROP = "rfs.sourceless.streamingPostings";
    public static final boolean DEFAULT_STREAMING_POSTINGS = true;
}
