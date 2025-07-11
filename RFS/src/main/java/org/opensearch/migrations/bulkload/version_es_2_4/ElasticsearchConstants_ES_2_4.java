package org.opensearch.migrations.bulkload.version_es_2_4;

/**
 * Central place to store constant field names and other ES 2.4 specific static configuration.
 */
public final class ElasticsearchConstants_ES_2_4 {
    private ElasticsearchConstants_ES_2_4() {}

    public static final String FIELD_SETTINGS;
    public static final String FIELD_PRIMARY_TERMS;
    public static final String FIELD_MAPPINGS;
    public static final String FIELD_COMPRESSED;
    public static final String FIELD_ALIASES;
    public static final int BUFFER_SIZE_IN_BYTES;
    public static final String SOFT_DELETES_FIELD;
    public static final boolean SOFT_DELETES_POSSIBLE;

    static {
        FIELD_SETTINGS = "settings";
        FIELD_PRIMARY_TERMS = "primary_terms";
        FIELD_MAPPINGS = "mappings";
        FIELD_COMPRESSED = "compressed";
        FIELD_ALIASES = "aliases";
        BUFFER_SIZE_IN_BYTES = 102400; // Default buffer size
        SOFT_DELETES_FIELD = null;
        SOFT_DELETES_POSSIBLE = false;

    }
}
