package com.rfs.version_es_7_10;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.rfs.common.GlobalMetadata;

public class GlobalMetadataFactory_ES_7_10 implements com.rfs.common.GlobalMetadata.Factory{

    public GlobalMetadata.Data fromJsonNode(JsonNode root) throws Exception {
        ObjectNode metadataRoot = (ObjectNode) root.get("meta-data");
        return new GlobalMetadataData_ES_7_10(metadataRoot);
    }

    public SmileFactory getSmileFactory() {
        return ElasticsearchConstants_ES_7_10.SMILE_FACTORY;
    }
    
}
