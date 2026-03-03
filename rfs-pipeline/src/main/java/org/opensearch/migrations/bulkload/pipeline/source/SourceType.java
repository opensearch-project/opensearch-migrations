package org.opensearch.migrations.bulkload.pipeline.source;

/**
 * Enumerates the available document source types for the migration pipeline.
 *
 * <p>Used by {@link DocumentSourceFactory} to select the appropriate {@link DocumentSource}
 * implementation based on configuration.
 */
public enum SourceType {
    /** Read documents from a real Elasticsearch/OpenSearch snapshot via Lucene. */
    SNAPSHOT,

    /** Generate synthetic documents without any real source — for testing and benchmarking. */
    SOURCELESS
}
