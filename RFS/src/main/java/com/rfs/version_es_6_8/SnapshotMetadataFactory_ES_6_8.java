package com.rfs.version_es_6_8;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;

import com.rfs.models.SnapshotMetadata;

public class SnapshotMetadataFactory_ES_6_8 implements SnapshotMetadata.Factory {

    /**
     * A version of the Elasticsearch approach simplified by assuming JSON; see here [1] for more details.
     * 
     * [1] https://github.com/elastic/elasticsearch/blob/6.8/server/src/main/java/org/elasticsearch/snapshots/SnapshotInfo.java#L583
     */
    @Override
    public SnapshotMetadata fromJsonNode(JsonNode root) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode objectNodeRoot = (ObjectNode) root;
        SnapshotMetadataData_ES_6_8 snapshotMetadata = mapper.treeToValue(
            objectNodeRoot.get("snapshot"),
            SnapshotMetadataData_ES_6_8.class
        );
        return snapshotMetadata;
    }

    @Override
    public SmileFactory getSmileFactory() {
        return ElasticsearchConstants_ES_6_8.SMILE_FACTORY;
    }
}
