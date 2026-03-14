package org.opensearch.migrations.bulkload.pipeline.adapter;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * ES-specific global metadata snapshot: templates, index templates, component templates.
 *
 * <p>This is an adapter-layer type, not part of the core IR. Only ES sources produce it
 * and only ES sinks consume it. Non-ES sources (S3, Solr) have no equivalent concept.
 *
 * @param templates          legacy templates, nullable
 * @param indexTemplates     index templates, nullable
 * @param componentTemplates component templates, nullable
 * @param indices            list of index names in the snapshot
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
