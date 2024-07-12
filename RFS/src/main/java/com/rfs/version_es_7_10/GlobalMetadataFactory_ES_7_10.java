package com.rfs.version_es_7_10;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;

import com.rfs.common.SnapshotRepo;
import com.rfs.models.GlobalMetadata;

public class GlobalMetadataFactory_ES_7_10 implements GlobalMetadata.Factory {
    private final SnapshotRepo.Provider repoDataProvider;

    public GlobalMetadataFactory_ES_7_10(SnapshotRepo.Provider repoDataProvider) {
        this.repoDataProvider = repoDataProvider;
    }

    @Override
    public GlobalMetadata fromJsonNode(JsonNode root) {
        ObjectNode metadataRoot = (ObjectNode) root.get("meta-data");
        return new GlobalMetadataData_ES_7_10(metadataRoot);
    }

    @Override
    public SmileFactory getSmileFactory() {
        return ElasticsearchConstants_ES_7_10.SMILE_FACTORY;
    }

    @Override
    public SnapshotRepo.Provider getRepoDataProvider() {
        return repoDataProvider;
    }

}
