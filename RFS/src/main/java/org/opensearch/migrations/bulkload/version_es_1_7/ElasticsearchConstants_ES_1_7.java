package org.opensearch.migrations.bulkload.version_es_1_7;

/**
 * Central place to store constant field names and other ES 2.4 specific static configuration.
 */
public final class ElasticsearchConstants_ES_1_7 {
    private ElasticsearchConstants_ES_1_7() {}

    public static final int BUFFER_SIZE_IN_BYTES = 102400;
    public static final String SOFT_DELETES_FIELD = null;
    public static final boolean SOFT_DELETES_POSSIBLE = false;
    public static final String FIELD_SETTINGS = "settings";
    public static final String FIELD_PRIMARY_TERMS = "primary_terms";
    public static final String FIELD_MAPPINGS = "mappings";
    public static final String FIELD_COMPRESSED = "compressed";
    public static final String FIELD_ALIASES = "aliases";
    public static final String FIELD_ROUTING_NUM_SHARDS = "routing_num_shards";
}
