package org.opensearch.migrations.bulkload.pipeline.source;

/**
 * Configuration for sourceless extraction mode. When this mode is opted into,
 * the pipeline generates synthetic documents instead of reading from a real snapshot.
 *
 * <p>This is useful for:
 * <ul>
 *   <li>Testing the write path without a source cluster</li>
 *   <li>Benchmarking target cluster ingestion performance</li>
 *   <li>Validating pipeline behavior with controlled data</li>
 * </ul>
 *
 * @param indexName     the index name to generate documents for
 * @param shardCount    number of shards to simulate (must be >= 1)
 * @param docsPerShard  number of documents per shard (must be >= 0)
 * @param docSizeBytes  approximate size of each generated document body in bytes (0 for default small docs)
 * @param deleteRatio   fraction of documents to generate as DELETE operations (0.0-1.0, default 0.0)
 * @param enableRouting whether to generate custom routing values on documents (default false)
 */
public record SourcelessExtractionConfig(
    String indexName,
    int shardCount,
    int docsPerShard,
    int docSizeBytes,
    double deleteRatio,
    boolean enableRouting
) {
    public SourcelessExtractionConfig {
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("indexName must not be null or blank");
        }
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount must be >= 1, got " + shardCount);
        }
        if (docsPerShard < 0) {
            throw new IllegalArgumentException("docsPerShard must be >= 0, got " + docsPerShard);
        }
        if (docSizeBytes < 0) {
            throw new IllegalArgumentException("docSizeBytes must be >= 0, got " + docSizeBytes);
        }
        if (deleteRatio < 0.0 || deleteRatio > 1.0) {
            throw new IllegalArgumentException("deleteRatio must be 0.0-1.0, got " + deleteRatio);
        }
    }

    /** 4-arg constructor for backward compatibility — no deletes, no routing. */
    public SourcelessExtractionConfig(String indexName, int shardCount, int docsPerShard, int docSizeBytes) {
        this(indexName, shardCount, docsPerShard, docSizeBytes, 0.0, false);
    }

    /** Create a config with default small document size, no deletes, no routing. */
    public static SourcelessExtractionConfig withDefaults(String indexName, int shardCount, int docsPerShard) {
        return new SourcelessExtractionConfig(indexName, shardCount, docsPerShard, 0, 0.0, false);
    }
}
