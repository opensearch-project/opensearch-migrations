package com.rfs.version_es_6_8;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.dataformat.smile.SmileFactory;
import com.fasterxml.jackson.dataformat.smile.SmileGenerator;

public class ElasticsearchConstants_ES_6_8 {
    public static final int BUFFER_SIZE_IN_BYTES;
    public static final SmileFactory SMILE_FACTORY;
    public static final String SOFT_DELETES_FIELD;
    public static final boolean SOFT_DELETES_POSSIBLE;

    static {
        // Taken from a running ES 6.8 process
        // https://github.com/elastic/elasticsearch/blob/6.8/server/src/main/java/org/elasticsearch/common/blobstore/fs/FsBlobStore.java#L49
        BUFFER_SIZE_IN_BYTES = 102400; // Default buffer size

        // Taken from:
        // https://github.com/elastic/elasticsearch/blob/6.8/libs/x-content/src/main/java/org/elasticsearch/common/xcontent/smile/SmileXContent.java#L55
        SmileFactory smileFactory = new SmileFactory();
        smileFactory.configure(SmileGenerator.Feature.ENCODE_BINARY_AS_7BIT, false);
        smileFactory.configure(SmileFactory.Feature.FAIL_ON_SYMBOL_HASH_OVERFLOW, false);
        smileFactory.configure(JsonGenerator.Feature.AUTO_CLOSE_JSON_CONTENT, false);
        smileFactory.configure(JsonParser.Feature.STRICT_DUPLICATE_DETECTION, false);
        SMILE_FACTORY = smileFactory;

        // Soft Deletes were added in 7.0
        SOFT_DELETES_FIELD = "";
        SOFT_DELETES_POSSIBLE = false;
    }

}
