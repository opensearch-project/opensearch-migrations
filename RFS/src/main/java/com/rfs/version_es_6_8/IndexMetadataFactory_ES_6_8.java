package com.rfs.version_es_6_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;

import com.rfs.common.SnapshotRepo;
import com.rfs.models.IndexMetadata;

public class IndexMetadataFactory_ES_6_8 implements IndexMetadata.Factory {
    private final SnapshotRepo.Provider repoDataProvider;

    public IndexMetadataFactory_ES_6_8(SnapshotRepo.Provider repoDataProvider) {
        this.repoDataProvider = repoDataProvider;
    }

    @Override
    public IndexMetadata fromJsonNode(JsonNode root, String indexId, String indexName) {
        ObjectNode objectNodeRoot = (ObjectNode) root.get(indexName);
        return new IndexMetadataData_ES_6_8(objectNodeRoot, indexId, indexName);
    }

    @Override
    public SmileFactory getSmileFactory() {
        return ElasticsearchConstants_ES_6_8.SMILE_FACTORY;
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
