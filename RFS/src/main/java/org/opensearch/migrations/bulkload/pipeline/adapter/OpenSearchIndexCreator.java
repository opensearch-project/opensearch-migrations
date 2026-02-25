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
        // After IndexMetadataConverter normalization, the "index." prefix is removed.
        // Handle both normalized (no prefix) and non-normalized (with prefix) settings.
        settings.remove("creation_date");
        settings.remove("provided_name");
        settings.remove("uuid");
        settings.remove("version");
        settings.remove("mapping");
        settings.remove("mapper");

        // Non-normalized: nested under "index" sub-object
        ObjectNodeUtils.removeFieldsByPath(settings, "index.creation_date");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.provided_name");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.uuid");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.version");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.mapping");
        ObjectNodeUtils.removeFieldsByPath(settings, "index.mapper");

        // Flat dotted keys (ES 1.7/2.4)
        settings.remove("index.creation_date");
        settings.remove("index.provided_name");
        settings.remove("index.uuid");
        settings.remove("index.version.created");
        settings.remove("index.mapping.single_type");
        settings.remove("index.mapper.dynamic");
    }
}
