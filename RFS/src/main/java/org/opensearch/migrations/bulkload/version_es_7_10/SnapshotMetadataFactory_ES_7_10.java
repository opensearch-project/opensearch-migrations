package org.opensearch.migrations.bulkload.version_es_7_10;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;

import org.opensearch.migrations.bulkload.models.SnapshotMetadata;

public class SnapshotMetadataFactory_ES_7_10 implements SnapshotMetadata.Factory {

    /**
     * A version of the Elasticsearch approach simplified by assuming JSON; see here [1] for more details.
     * 
     * [1] https://github.com/elastic/elasticsearch/blob/6.8/server/src/main/java/org/elasticsearch/snapshots/SnapshotInfo.java#L583
     */
    @Override
    public SnapshotMetadata fromJsonNode(JsonNode root) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        ObjectNode objectNodeRoot = (ObjectNode) root;
        return mapper.treeToValue(
            objectNodeRoot.get("snapshot"),
            SnapshotMetadataData_ES_7_10.class
        );
    }

    @Override
    public SmileFactory getSmileFactory() {
        return ElasticsearchConstants_ES_7_10.SMILE_FACTORY;
    }
}
