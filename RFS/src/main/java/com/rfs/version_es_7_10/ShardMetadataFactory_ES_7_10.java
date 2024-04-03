package com.rfs.version_es_7_10;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.rfs.common.ShardMetadata;

public class ShardMetadataFactory_ES_7_10 implements ShardMetadata.Factory {
    

    public ShardMetadata.Data fromJsonNode(JsonNode root, String indexId, String indexName, int shardId) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(ShardMetadataData_ES_7_10.FileInfoRaw.class, new ShardMetadataData_ES_7_10.FileInfoRawDeserializer());
        objectMapper.registerModule(module);

        ObjectNode objectNodeRoot = (ObjectNode) root;
        ShardMetadataData_ES_7_10.DataRaw shardMetadataRaw = objectMapper.treeToValue(objectNodeRoot, ShardMetadataData_ES_7_10.DataRaw.class);
        return new ShardMetadataData_ES_7_10(
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
    }

    public SmileFactory getSmileFactory() {
        return ElasticsearchConstants_ES_7_10.SMILE_FACTORY;
    }
}
