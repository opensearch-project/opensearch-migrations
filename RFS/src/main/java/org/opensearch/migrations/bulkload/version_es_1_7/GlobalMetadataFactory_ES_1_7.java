package org.opensearch.migrations.bulkload.version_es_1_7;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.models.GlobalMetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;

public class GlobalMetadataFactory_ES_1_7 implements GlobalMetadata.Factory {
    private final SnapshotRepo.Provider repoDataProvider;
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    public GlobalMetadataFactory_ES_1_7(SnapshotRepo.Provider repoDataProvider) {
        this.repoDataProvider = repoDataProvider;
    }

    @Override
    public GlobalMetadata fromRepo(String snapshotName) {
        try {
            SnapshotRepoES17 realProvider = (SnapshotRepoES17) repoDataProvider;
            Path filePath = realProvider.getGlobalMetadataFile(snapshotName);
            try (InputStream inputStream = Files.newInputStream(filePath)) {
                JsonNode root = objectMapper.readTree(inputStream);
                return fromJsonNode(root);
            }
        } catch (Exception e) {
            throw new GlobalMetadata.CantReadGlobalMetadataFromSnapshot(snapshotName, e);
        }
    }

    @Override
    public GlobalMetadata fromJsonNode(JsonNode root) {
        JsonNode metadataRoot = root.get("meta-data");
        if (metadataRoot == null || !metadataRoot.isObject()) {
            throw new IllegalArgumentException("Expected 'meta-data' object in root!");
        }
        return new GlobalMetadataData_ES_1_7((ObjectNode) metadataRoot);
    }

    @Override
    public SmileFactory getSmileFactory() {
        // ES 1.7 snapshots are always JSON, no Smile encoding
        throw new IllegalArgumentException("ES 1.x does not use Smile encoding for global metadata");
    }

    @Override
    public SnapshotRepo.Provider getRepoDataProvider() {
        return repoDataProvider;
    }
}
