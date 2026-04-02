package org.opensearch.migrations.bulkload.solr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import org.opensearch.migrations.bulkload.common.SnapshotRepo.Provider;
import org.opensearch.migrations.bulkload.models.IndexMetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RequiredArgsConstructor
@Slf4j
public class SolrIndexMetadataFactory implements IndexMetadata.Factory {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SolrClient client;
    private ObjectNode indexData;

    ObjectNode getIndexData() {
        if (indexData == null) {
            indexData = buildIndexData();
        }
        return indexData;
    }

    private ObjectNode buildIndexData() {
        var result = MAPPER.createObjectNode();
        try {
            List<String> collections = client.listCollections();
            for (var collection : collections) {
                var schema = client.getSchema(collection);
                var schemaNode = schema.path("schema");
                var mappings = SolrSchemaConverter.convertToOpenSearchMappings(
                    schemaNode.path("fields"),
                    schemaNode.path("dynamicFields"),
                    schemaNode.path("copyFields"),
                    schemaNode.path("fieldTypes")
                );

                var indexNode = MAPPER.createObjectNode();
                indexNode.set("mappings", mappings);
                indexNode.set("aliases", MAPPER.createObjectNode());
                var settings = MAPPER.createObjectNode();
                var indexSettings = MAPPER.createObjectNode();
                indexSettings.put("number_of_shards", "1");
                indexSettings.put("number_of_replicas", "1");
                settings.set("index", indexSettings);
                indexNode.set("settings", settings);

                result.set(collection, indexNode);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read Solr collection metadata", e);
        }
        return result;
    }

    @Override
    public IndexMetadata fromRepo(String snapshotName, String indexName) {
        log.info("Using Solr remote cluster directly");
        return new SolrIndexMetadata(indexName, (ObjectNode) getIndexData().get(indexName));
    }

    @Override
    public Provider getRepoDataProvider() {
        return new SolrSnapshotDataProvider(getIndexData());
    }

    @Override
    public IndexMetadata fromJsonNode(JsonNode root, String indexId, String indexName) {
        throw new UnsupportedOperationException("Unimplemented method 'fromJsonNode'");
    }

    @Override
    public SmileFactory getSmileFactory() {
        throw new UnsupportedOperationException("Unimplemented method 'getSmileFactory'");
    }

    @Override
    public String getIndexFileId(String snapshotName, String indexName) {
        throw new UnsupportedOperationException("Unimplemented method 'getIndexFileId'");
    }
}
