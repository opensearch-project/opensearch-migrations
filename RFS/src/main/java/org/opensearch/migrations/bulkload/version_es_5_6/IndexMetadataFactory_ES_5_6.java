package org.opensearch.migrations.bulkload.version_es_5_6;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.models.IndexMetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;

public class IndexMetadataFactory_ES_5_6 implements IndexMetadata.Factory {
    private final SnapshotRepo.Provider repoDataProvider;

    public IndexMetadataFactory_ES_5_6(SnapshotRepo.Provider repoDataProvider) {
        this.repoDataProvider = repoDataProvider;
    }

    @Override
    public IndexMetadata fromJsonNode(JsonNode root, String indexId, String indexName) {
        ObjectNode objectNodeRoot = (ObjectNode) root.get(indexName);
        return new IndexMetadataData_ES_5_6(objectNodeRoot, indexId, indexName);
    }

    @Override
    public SmileFactory getSmileFactory() {
        return ElasticsearchConstants_ES_5_6.SMILE_FACTORY;
    }

    @Override
    public String getIndexFileId(String snapshotName, String indexName) {
        return repoDataProvider.getSnapshotId(snapshotName);
    }

    @Override
    public SnapshotRepo.Provider getRepoDataProvider() {
        return repoDataProvider;
    }
}
