package com.rfs.version_es_7_10;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.fasterxml.jackson.dataformat.smile.SmileGenerator;

public class ElasticsearchConstants_ES_7_10 {
    public static final int BUFFER_SIZE_IN_BYTES;
    public static final SmileFactory SMILE_FACTORY;
    public static final String SOFT_DELETES_FIELD;
    public static final boolean SOFT_DELETES_POSSIBLE;

    static {
        // https://github.com/elastic/elasticsearch/blob/7.10/server/src/main/java/org/elasticsearch/repositories/blobstore/BlobStoreRepository.java#L209
        BUFFER_SIZE_IN_BYTES = 128 * 1024; // Default buffer size

        // Taken from:
        // https://github.com/elastic/elasticsearch/blob/7.10/libs/x-content/src/main/java/org/elasticsearch/common/xcontent/smile/SmileXContent.java#L54
        SmileFactory smileFactory = new SmileFactory();
        smileFactory.configure(SmileGenerator.Feature.ENCODE_BINARY_AS_7BIT, false);
        smileFactory.configure(SmileFactory.Feature.FAIL_ON_SYMBOL_HASH_OVERFLOW, false);
        smileFactory.configure(JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT, false);
        smileFactory.configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, false);
        SMILE_FACTORY = smileFactory;

        // Taken from:
        // https://github.com/elastic/elasticsearch/blob/v7.10.2/server/src/main/java/org/elasticsearch/common/lucene/Lucene.java#L110
        SOFT_DELETES_FIELD = "__soft_deletes";

        // Soft Deletes were added in 7.0
        SOFT_DELETES_POSSIBLE = true;
    }

}
