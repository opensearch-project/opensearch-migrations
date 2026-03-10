package org.opensearch.migrations.bulkload.pipeline.ir;

import java.util.Map;
import java.util.Objects;

/**
 * Source-agnostic metadata for a collection (index, bucket, Solr collection, etc.).
 *
 * <p>The pipeline core uses this to communicate collection metadata between source and sink.
 * Source-specific details (ES mappings, Solr configsets, etc.) are carried in the opaque
 * {@code sourceConfig} map — the pipeline never interprets them, but the sink adapter can
 * read them if the source and sink share a common format.
 *
 * @param name           the collection name
 * @param partitionCount hint for the target — 0 means "use target default"
 * @param sourceConfig   opaque source-specific configuration (e.g. ES mappings, settings, aliases)
 */
public record CollectionMetadata(
    String name,
    int partitionCount,
    Map<String, Object> sourceConfig
) {
    public CollectionMetadata {
        Objects.requireNonNull(name, "name must not be null");
        if (partitionCount < 0) {
            throw new IllegalArgumentException("partitionCount must be >= 0, got " + partitionCount);
        }
        sourceConfig = sourceConfig != null ? Map.copyOf(sourceConfig) : Map.of();
    }
}
