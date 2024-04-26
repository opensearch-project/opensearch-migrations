package com.rfs.version_es_6_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.rfs.common.GlobalMetadata;

public class GlobalMetadataFactory_ES_6_8 implements com.rfs.common.GlobalMetadata.Factory{
    
    @Override
    public GlobalMetadata.Data fromJsonNode(JsonNode root) throws Exception {
        ObjectNode metadataRoot = (ObjectNode) root.get("meta-data");
        return new GlobalMetadataData_ES_6_8(metadataRoot);
    }

    @Override
    public SmileFactory getSmileFactory() {
        return ElasticsearchConstants_ES_6_8.SMILE_FACTORY;
    }
}
