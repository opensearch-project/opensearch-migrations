package org.opensearch.migrations.bulkload.version_es_2_4;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.fasterxml.jackson.dataformat.smile.SmileGenerator;

/**
 * Central place to store constant field names and other ES 2.4 specific static configuration.
 */
public final class ElasticsearchConstants_ES_2_4 {
    private ElasticsearchConstants_ES_2_4() {}

    public static final int BUFFER_SIZE_IN_BYTES;
    public static final SmileFactory SMILE_FACTORY;
    public static final String SOFT_DELETES_FIELD = null;
    public static final boolean SOFT_DELETES_POSSIBLE = false;
    public static final String FIELD_SETTINGS = "settings";
    public static final String FIELD_MAPPINGS = "mappings";
    public static final String INDICES_DIR_NAME = "indices";

    static {
        // Taken from : https://github.com/opensearch-project/OpenSearch/blob/e41ae25b1e4ce15e2281de4f8699beea09584ec2/core/src/main/java/org/elasticsearch/common/blobstore/fs/FsBlobStore.java#L49
        BUFFER_SIZE_IN_BYTES = 102400;

        // Taken from : https://github.com/opensearch-project/OpenSearch/blob/e41ae25b1e4ce15e2281de4f8699beea09584ec2/core/src/main/java/org/elasticsearch/common/xcontent/smile/SmileXContent.java
        SMILE_FACTORY = SmileFactory.builder()
                .configure(SmileGenerator.Feature.ENCODE_BINARY_AS_7BIT, false)
                .configure(JsonFactory.Feature.FAIL_ON_SYMBOL_HASH_OVERFLOW, false)
                .build();
        SMILE_FACTORY.disable(JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT);
    }
}
