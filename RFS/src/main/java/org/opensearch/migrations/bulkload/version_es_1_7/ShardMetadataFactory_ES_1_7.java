package org.opensearch.migrations.bulkload.version_es_1_7;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.opensearch.migrations.bulkload.common.ObjectMapperFactory;
import org.opensearch.migrations.bulkload.common.SnapshotRepo;
import org.opensearch.migrations.bulkload.models.ShardMetadata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ShardMetadataFactory_ES_1_7 implements ShardMetadata.Factory {
    protected final SnapshotRepo.Provider repoDataProvider;

    @Override
    public ShardMetadata fromJsonNode(JsonNode root, String indexId, String indexName, int shardId) {
        ObjectMapper objectMapper = ObjectMapperFactory.createDefaultMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(
                ShardMetadataData_ES_1_7.FileInfoRaw.class,
                new ShardMetadataData_ES_1_7.FileInfoRawDeserializer()
        );
        objectMapper.registerModule(module);

        try {
            ObjectNode objectNodeRoot = (ObjectNode) root;
            ShardMetadataData_ES_1_7.DataRaw shardMetadataRaw = objectMapper.treeToValue(
                    objectNodeRoot,
                    ShardMetadataData_ES_1_7.DataRaw.class
            );
            return new ShardMetadataData_ES_1_7(
                    shardMetadataRaw.name,
                    indexName,
                    indexId,
                    shardId,
                    shardMetadataRaw.indexVersion,
                    shardMetadataRaw.startTime,
                    shardMetadataRaw.time,
                    shardMetadataRaw.numberOfFiles,
                    shardMetadataRaw.totalSize,
                    shardMetadataRaw.files
            );
        } catch (Exception e) {
            throw new ShardMetadata.CouldNotParseShardMetadata(
                    "Could not parse shard metadata for Index " + indexId + ", Shard " + shardId,
                    e
            );
        }

    }

    @Override
    public ShardMetadata fromRepo(String snapshotName, String indexName, int shardId) {
        try {
            SnapshotRepoES17 repo = (SnapshotRepoES17) getRepoDataProvider();
            String snapshotId = repo.getSnapshotId(snapshotName);
            String indexId = repo.getIndexId(indexName);
            Path path = repo.getShardMetadataFilePath(snapshotId, indexId, shardId);

            try (InputStream in = Files.newInputStream(path)) {
                JsonNode root = ObjectMapperFactory.createDefaultMapper().readTree(in);
                return fromJsonNode(root, indexId, indexName, shardId);
            }
        } catch (Exception e) {
            throw new ShardMetadata.CouldNotParseShardMetadata(
                    "Could not parse shard metadata for Snapshot " + snapshotName +
                            ", Index " + indexName + ", Shard " + shardId, e);
        }
    }

    @Override
    public SmileFactory getSmileFactory() {
        return null;
    }

    @Override
    public SnapshotRepo.Provider getRepoDataProvider() {
        return repoDataProvider;
    }
}
