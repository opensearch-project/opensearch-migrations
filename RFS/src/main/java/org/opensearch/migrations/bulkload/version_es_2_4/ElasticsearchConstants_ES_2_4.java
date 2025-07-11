package org.opensearch.migrations.bulkload.version_es_2_4;

/**
 * Central place to store constant field names and other ES 2.4 specific static configuration.
 */
public final class ElasticsearchConstants_ES_2_4 {
    private ElasticsearchConstants_ES_2_4() {
        // Utility class; prevent instantiation
    }

    public static final String FIELD_SETTINGS = "settings";
    public static final String FIELD_PRIMARY_TERMS = "primary_terms";
    public static final String FIELD_MAPPINGS = "mappings";
    public static final String FIELD_COMPRESSED = "compressed";
    public static final String FIELD_ALIASES = "aliases";
}
