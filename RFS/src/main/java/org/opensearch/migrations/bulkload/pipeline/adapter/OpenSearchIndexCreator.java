package org.opensearch.migrations.bulkload.pipeline.adapter;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Shared helper for creating an index on an OpenSearch cluster from pipeline IR metadata.
 * Used by both {@link OpenSearchDocumentSink} and {@link OpenSearchMetadataSink}
 * to avoid duplicating the body-building logic.
 */
final class OpenSearchIndexCreator {

    private OpenSearchIndexCreator() {}

    static void createIndex(OpenSearchClient client, IndexMetadataSnapshot metadata, ObjectMapper mapper) {
        ObjectNode body = mapper.createObjectNode();
        if (metadata.mappings() != null) {
            body.set("mappings", metadata.mappings());
        }
        if (metadata.settings() != null) {
            body.set("settings", metadata.settings());
        }
        if (metadata.aliases() != null) {
            body.set("aliases", metadata.aliases());
        }
        client.createIndex(metadata.indexName(), body, null);
    }
}
