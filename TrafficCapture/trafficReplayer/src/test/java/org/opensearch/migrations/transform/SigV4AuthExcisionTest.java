package org.opensearch.migrations.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CharStreams;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opensearch.migrations.replay.datahandlers.JsonAccumulator;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;

public class SigV4AuthExcisionTest {

    static ObjectMapper objectMapper = new ObjectMapper();


    static InputStream getInputStreamForTypeMappingResource(String resourceName) {
        return SigV4AuthExcisionTest.class.getResourceAsStream("/sampleJsonDocuments/sigV4/" +
                resourceName);
    }
/*
    @Test
    public void removesSigV4HeadersFromSignedRequest() throws Exception {
        var json = parseJsonFromResourceName("signed_request_input.txt");
        transformAndVerifyResult(json, "signed_request_output.txt");
    }

    @Test
    public void doesntRemoveHeadersFromUnsignedRequest() throws Exception {
        var json = parseJsonFromResourceName("unsigned_request.txt");
        transformAndVerifyResult(json, "unsigned_request.txt");
    }


 */


    private static Object parseJsonFromResourceName(String resourceName) throws Exception {
        var jsonAccumulator = new JsonAccumulator();
        try (var resourceStream = getInputStreamForTypeMappingResource(resourceName);
             var isr = new InputStreamReader(resourceStream, StandardCharsets.UTF_8)) {
            var expectedBytes = CharStreams.toString(isr).getBytes(StandardCharsets.UTF_8);
            return jsonAccumulator.consumeByteBuffer(ByteBuffer.wrap(expectedBytes));
        }
    }

    private static void transformAndVerifyResult(Object json, String expectedValueSource) throws Exception {
        var jsonTransformer = getJsonTransformer();
        json = jsonTransformer.transformJson(json);
        var jsonAsStr = objectMapper.writeValueAsString(json);
        Object expectedObject = parseJsonFromResourceName(expectedValueSource);
        var expectedValue = objectMapper.writeValueAsString(expectedObject);
        Assertions.assertEquals(expectedValue, jsonAsStr);
    }

    static JsonTransformer getJsonTransformer() {
        return new SigV4ExcisionJsonTransformer();
    }
}