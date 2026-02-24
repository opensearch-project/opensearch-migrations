package org.opensearch.migrations.bulkload.pipeline.adapter;

import org.opensearch.migrations.bulkload.common.OpenSearchClient;
import org.opensearch.migrations.bulkload.pipeline.ir.IndexMetadataSnapshot;
import org.opensearch.migrations.parsing.ObjectNodeUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Shared helper for creating an index on an OpenSearch cluster from pipeline IR metadata.
 * Used by both {@link OpenSearchDocumentSink} and {@link OpenSearchMetadataSink}
 * to avoid duplicating the body-building logic.
 *
 * <p>Only strips internal settings (which are always invalid on target).
 * Type mapping normalization and field type upgrades are delegated to the transformer chain
 * ({@link org.opensearch.migrations.bulkload.transformers.CanonicalTransformer}), which is
 * the canonical place for version-specific transformations.
 */
final class OpenSearchIndexCreator {

    // Flat dotted keys (for flat settings from ES 1.7/2.4 where keys are literal strings like "index.creation_date").
    private static final String[] FLAT_SETTINGS = {
        "index.creation_date", "index.provided_name", "index.uuid", "index.version.created",
        "index.mapping.single_type", "index.mapper.dynamic"
    };

    private OpenSearchIndexCreator() {}

    static void createIndex(OpenSearchClient client, IndexMetadataSnapshot metadata, ObjectMapper mapper) {
        ObjectNode body = mapper.createObjectNode();
        if (metadata.mappings() != null) {
            body.set("mappings", metadata.mappings().deepCopy());
        }
        if (metadata.settings() != null) {
            ObjectNode settings = metadata.settings().deepCopy();
            stripInternalSettings(settings);
            body.set("settings", settings);
        }
        if (metadata.aliases() != null) {
            body.set("aliases", metadata.aliases());
        }
        client.createIndex(metadata.indexName(), body, null);
    }

    private static void stripInternalSettings(ObjectNode settings) {
        // Handle tree-structured settings (nested under "index" sub-object)
        ObjectNodeUtils.removeFieldsByPath(settings, "index.creation_date");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.provided_name");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.uuid");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.version");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.mapping.single_type");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.mapper.dynamic");

        // Handle flat dotted keys (e.g. ES 1.7/2.4 where the key is literally "index.creation_date")
        for (var key : FLAT_SETTINGS) {
            settings.remove(key);
        }
    }
}
