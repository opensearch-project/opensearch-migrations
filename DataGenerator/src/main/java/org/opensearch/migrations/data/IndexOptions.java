package org.opensearch.migrations.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Options index configuration */
public class IndexOptions {
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Improvement to add more flexibility with these values */
    public ObjectNode indexSettings = mapper.createObjectNode()
        .put("index.number_of_shards", 5)
        .put("index.number_of_replicas", 0)
        .put("index.queries.cache.enabled", false)
        .put("index.requests.cache.enable", false);
}
