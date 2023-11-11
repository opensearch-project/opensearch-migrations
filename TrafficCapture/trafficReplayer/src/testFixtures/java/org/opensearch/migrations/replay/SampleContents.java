package org.opensearch.migrations.replay;

import java.nio.charset.StandardCharsets;

public class SampleContents {

    public static final String SAMPLE_REQUEST_MESSAGE_JSON_RESOURCE_NAME = "/sampleRequestMessage.json";

    public static String loadSampleJsonRequestAsString() throws Exception {
        try (var inputStream =
                 SampleContents.class.getResourceAsStream(SAMPLE_REQUEST_MESSAGE_JSON_RESOURCE_NAME)) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
