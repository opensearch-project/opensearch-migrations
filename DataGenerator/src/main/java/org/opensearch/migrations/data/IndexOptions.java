package org.opensearch.migrations.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Options index configuration */
public class IndexOptions {
    public static final String PROP_NUMBER_OF_SHARDS = "index.number_of_shards";
    public static final String PROP_NUMBER_OF_REPLICAS = "index.number_of_replicas";
    public static final String PROP_QUERIES_CACHE_ENABLED = "index.queries.cache.enabled";
    public static final String PROP_REQUESTS_CACHE_ENABLED = "index.requests.cache.enable";

    private static final ObjectMapper mapper = new ObjectMapper();

    /** Improvement to add more flexibility with these values */
    public final ObjectNode indexSettings = mapper.createObjectNode()
        .put(PROP_NUMBER_OF_SHARDS, 5)
        .put(PROP_NUMBER_OF_REPLICAS, 0)
        .put(PROP_QUERIES_CACHE_ENABLED, false)
        .put(PROP_REQUESTS_CACHE_ENABLED, false);
}
