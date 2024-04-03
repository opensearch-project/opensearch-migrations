package com.rfs.version_es_7_10;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.rfs.common.IndexMetadata;
import com.rfs.common.SnapshotRepo;

public class IndexMetadataFactory_ES_7_10 implements com.rfs.common.IndexMetadata.Factory {
    
    public IndexMetadata.Data fromJsonNode(JsonNode root, String indexId, String indexName) throws Exception {
        ObjectNode objectNodeRoot = (ObjectNode) root.get(indexName);
        return new IndexMetadataData_ES_7_10(objectNodeRoot, indexId, indexName);
    }

    public SmileFactory getSmileFactory() {
        return ElasticsearchConstants_ES_7_10.SMILE_FACTORY;
    }

    public String getIndexFileId(SnapshotRepo.Provider repoDataProvider, String snapshotName, String indexName) {
        SnapshotRepoProvider_ES_7_10 providerES710 = (SnapshotRepoProvider_ES_7_10) repoDataProvider;
        return providerES710.getIndexMetadataId(snapshotName, indexName);
    }
    
}
