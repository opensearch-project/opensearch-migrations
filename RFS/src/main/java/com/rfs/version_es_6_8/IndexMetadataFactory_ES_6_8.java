package com.rfs.version_es_6_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.rfs.common.IndexMetadata;
import com.rfs.common.SnapshotRepo;

public class IndexMetadataFactory_ES_6_8 implements com.rfs.common.IndexMetadata.Factory {
    
    public IndexMetadata.Data fromJsonNode(JsonNode root, String indexId, String indexName) throws Exception {
        ObjectNode objectNodeRoot = (ObjectNode) root.get(indexName);
        return new IndexMetadataData_ES_6_8(objectNodeRoot, indexId, indexName);
    }

    public SmileFactory getSmileFactory() {
        return ElasticsearchConstants_ES_6_8.SMILE_FACTORY;
    }

    public String getIndexFileId(SnapshotRepo.Provider repoDataProvider, String snapshotName, String indexName) {
        return repoDataProvider.getSnapshotId(snapshotName);
    }
}
