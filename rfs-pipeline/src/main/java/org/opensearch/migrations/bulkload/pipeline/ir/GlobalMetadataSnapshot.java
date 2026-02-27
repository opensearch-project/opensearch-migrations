package org.opensearch.migrations.bulkload.pipeline.ir;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lucene-agnostic global metadata snapshot — templates, component templates, and index templates
 * from a cluster or snapshot.
 *
 * <p>Unlike the existing {@code GlobalMetadata} interface, this is a simple data carrier
 * with no factory methods or repo-access logic.
 *
 * @param templates          legacy templates (ES 5/6 style), nullable
 * @param indexTemplates     composable index templates (ES 7.8+ style), nullable
 * @param componentTemplates component templates (ES 7.8+ style), nullable
 * @param indices            list of index names present in the snapshot, must not be null
 */
public record GlobalMetadataSnapshot(
    ObjectNode templates,
    ObjectNode indexTemplates,
    ObjectNode componentTemplates,
    List<String> indices
) {
    public GlobalMetadataSnapshot {
        Objects.requireNonNull(indices, "indices must not be null");
        indices = List.copyOf(indices); // defensive copy for immutability
    }
}
