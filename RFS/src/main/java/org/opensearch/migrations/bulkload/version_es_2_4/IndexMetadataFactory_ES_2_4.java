package org.opensearch.migrations.bulkload.version_es_2_4;

import org.opensearch.migrations.bulkload.models.IndexMetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;

public class IndexMetadataFactory_ES_2_4 implements IndexMetadata.Factory {
    private final SnapshotRepoES24 repoProvider;

    public IndexMetadataFactory_ES_2_4(SnapshotRepoES24 repoProvider) {
        this.repoProvider = repoProvider;
    }

    @Override
    public IndexMetadata fromJsonNode(JsonNode root, String indexId, String indexName) {
        ObjectNode objectNodeRoot = (ObjectNode) root.get(indexName);
        return new IndexMetadataData_ES_2_4(objectNodeRoot, indexName);
    }

    @Override
    public SmileFactory getSmileFactory() {
        return ElasticsearchConstants_ES_2_4.SMILE_FACTORY;
    }

    @Override
    public String getIndexFileId(String snapshotName, String indexName) {
        // In ES 2.4, the index file id uses the snapshotName as it's suffix
        return snapshotName;
    }

    @Override
    public SnapshotRepoES24 getRepoDataProvider() {
        return repoProvider;
    }
}
