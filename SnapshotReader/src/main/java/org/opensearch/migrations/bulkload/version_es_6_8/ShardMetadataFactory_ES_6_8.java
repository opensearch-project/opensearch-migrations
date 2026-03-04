package org.opensearch.migrations.bulkload.version_es_6_8;

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
public class ShardMetadataFactory_ES_6_8 implements ShardMetadata.Factory {
    protected final SnapshotRepo.Provider repoDataProvider;

    @Override
    public ShardMetadata fromJsonNode(JsonNode root, String indexId, String indexName, int shardId) {
        ObjectMapper objectMapper = ObjectMapperFactory.createDefaultMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(
            ShardMetadataData_ES_6_8.FileInfoRaw.class,
            new ShardMetadataData_ES_6_8.FileInfoRawDeserializer()
        );
        objectMapper.registerModule(module);

        try {
            ObjectNode objectNodeRoot = (ObjectNode) root;
            ShardMetadataData_ES_6_8.DataRaw shardMetadataRaw = objectMapper.treeToValue(
                objectNodeRoot,
                ShardMetadataData_ES_6_8.DataRaw.class
            );
            return new ShardMetadataData_ES_6_8(
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
    public SmileFactory getSmileFactory() {
        return ElasticsearchConstants_ES_6_8.SMILE_FACTORY;
    }

    @Override
    public SnapshotRepo.Provider getRepoDataProvider() {
        return repoDataProvider;
    }
}
