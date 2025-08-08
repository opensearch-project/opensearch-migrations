package org.opensearch.migrations.bulkload.version_es_1_7;

/**
 * Central place to store constant field names and static configuration.
 */
public final class ElasticsearchConstants_ES_1_7 {
    private ElasticsearchConstants_ES_1_7() {}

    public static final int BUFFER_SIZE_IN_BYTES = 102400;
    public static final String SOFT_DELETES_FIELD = null;
    public static final boolean SOFT_DELETES_POSSIBLE = false;
    public static final String FIELD_SETTINGS = "settings";
    public static final String FIELD_MAPPINGS = "mappings";
    public static final String INDICES_DIR_NAME = "indices";
    public static final String METADATA_PREFIX = "metadata-";
    public static final String SNAPSHOT_PREFIX = "snapshot-";
}
