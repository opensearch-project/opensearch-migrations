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

    // --- Position-gap stopword filler ---

    /**
     * Token used to fill skipped Lucene positions when reconstructing analyzed-text fields
     * from postings. ES preserves position increments for stop-word-filtered tokens
     * (e.g. "i like the tree" with stopword "the" indexes at positions 0, 1, 3 — position 2
     * is consumed by "the" but the term itself is dropped). When the reconstructor walks the
     * postings of such a field it sees only [0:i, 1:like, 3:tree] and would naively join with
     * spaces, producing "i like tree" — a string that OS will re-tokenize at consecutive
     * positions [0, 1, 2] and silently fix up proximity / slop / phrase queries that the
     * original document deliberately did not match.
     *
     * <p>When this tunable is set to a non-empty token (e.g. {@code a}), the reconstructor
     * inserts {@code (positionGap - 1)} copies of the token between adjacent postings so that
     * OS's analyzer (which is expected to have the SAME token configured as a stopword)
     * re-creates the original position increments while indexing — the token contributes a
     * position increment but is then filtered out of the postings, exactly mirroring ES.
     *
     * <p>System property: {@code -Drfs.position.gap.stopword=<token>}
     * <p>Env var fallback: {@code RFS_POSITION_GAP_STOPWORD}
     * <p>Default: unset — multi-space gap-only behaviour, no token inserted.
     *
     * <p>The token MUST already be present in the target index's analyzer stop-word list for
     * the workaround to be effective; otherwise OS will index the filler as a real term and
     * the reconstructed _source will leak into search results. Migrations targeting indices
     * with the {@code english} / {@code standard} stop-word filters can use {@code a} as a
     * safe default.
     */
    public static final String POSITION_GAP_STOPWORD_PROP = "rfs.position.gap.stopword";
    public static final String POSITION_GAP_STOPWORD_ENV = "RFS_POSITION_GAP_STOPWORD";

    /** @return the configured position-gap stopword, or {@code null} when unset / blank. */
    public static String positionGapStopword() {
        String raw = System.getProperty(POSITION_GAP_STOPWORD_PROP);
        if (raw == null || raw.isBlank()) raw = System.getenv(POSITION_GAP_STOPWORD_ENV);
        return (raw == null || raw.isBlank()) ? null : raw.trim();
    }
}
