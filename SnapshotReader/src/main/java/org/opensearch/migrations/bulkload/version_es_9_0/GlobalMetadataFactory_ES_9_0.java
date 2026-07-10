package org.opensearch.migrations.bulkload.version_es_9_0;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;

public class GlobalMetadataFactory_ES_9_0 implements GlobalMetadata.Factory {
    private final SnapshotRepo.Provider repoDataProvider;

    public GlobalMetadataFactory_ES_9_0(SnapshotRepo.Provider repoDataProvider) {
        this.repoDataProvider = repoDataProvider;
    }

    @Override
    public GlobalMetadata fromJsonNode(JsonNode root) {
        ObjectNode metadataRoot = (ObjectNode) root.get("meta-data");
        return new GlobalMetadataData_ES_9_0(metadataRoot);
    }

    @Override
    public SmileFactory getSmileFactory() {
        return ElasticsearchConstants_ES_9_0.SMILE_FACTORY;
    }

    @Override
    public SnapshotRepo.Provider getRepoDataProvider() {
        return repoDataProvider;
    }

}
