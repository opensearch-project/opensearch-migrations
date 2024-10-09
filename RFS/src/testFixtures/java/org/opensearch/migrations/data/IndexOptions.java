package org.opensearch.migrations.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class IndexOptions {
    private static final ObjectMapper mapper = new ObjectMapper();
    
    public ObjectNode indexSettings = mapper.createObjectNode()
        .put("index.number_of_shards", 5)
        .put("index.number_of_replicas", 1)
        .put("index.queries.cache.enabled", true)
        .put("index.requests.cache.enable", true);
}
