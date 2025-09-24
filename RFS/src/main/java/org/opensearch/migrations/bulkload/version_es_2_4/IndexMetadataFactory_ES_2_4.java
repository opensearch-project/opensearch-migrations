package org.opensearch.migrations.bulkload.version_es_2_4;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.models.IndexMetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;

public class IndexMetadataFactory_ES_2_4 implements IndexMetadata.Factory {
    private final SnapshotRepo.Provider repoProvider;

    public IndexMetadataFactory_ES_2_4(SnapshotRepo.Provider repoProvider) {
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
    public SnapshotRepo.Provider getRepoDataProvider() {
        return repoProvider;
    }
}
