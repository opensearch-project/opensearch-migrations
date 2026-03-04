package org.opensearch.migrations.bulkload.version_es_2_4;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;

public class GlobalMetadataFactory_ES_2_4 implements GlobalMetadata.Factory {
    private final SnapshotRepo.Provider repoDataProvider;

    public GlobalMetadataFactory_ES_2_4(SnapshotRepo.Provider repoDataProvider) {
        this.repoDataProvider = repoDataProvider;
    }

    @Override
    public GlobalMetadata fromJsonNode(JsonNode root) {
        JsonNode metadataRoot = root.get("meta-data");
        if (metadataRoot == null || !metadataRoot.isObject()) {
            throw new IllegalArgumentException("Expected 'meta-data' object in root!");
        }
        return new GlobalMetadataData_ES_2_4((ObjectNode) metadataRoot);
    }

    @Override
    public SmileFactory getSmileFactory() {
        return ElasticsearchConstants_ES_2_4.SMILE_FACTORY;
    }

    @Override
    public SnapshotRepo.Provider getRepoDataProvider() {
        return repoDataProvider;
    }
}
