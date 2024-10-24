package org.opensearch.migrations.transform;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.opensearch.migrations.replay.datahandlers.JsonAccumulator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.CharStreams;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TypeMappingsExcisionTest {

    static final TypeReference<LinkedHashMap<String, Object>> TYPE_REFERENCE_FOR_MAP_TYPE = new TypeReference<>() {
    };

    static ObjectMapper objectMapper = new ObjectMapper();

    static InputStream getInputStreamForTypeMappingResource(String resourceName) {
        return TypeMappingsExcisionTest.class.getResourceAsStream("/sampleJsonDocuments/typeMappings/" + resourceName);
    }

    @Test
    public void removesTypeMappingsFrom_indexCreation() throws Exception {
        var json = parseJsonFromResourceName("put_index_input.txt");
        transformAndVerifyResult(json, "put_index_output.txt");
    }

    @Test
    public void removesTypeMappingsFrom_documentPut() throws Exception {
        var json = parseJsonFromResourceName("put_document_input.txt");
        transformAndVerifyResult(json, "put_document_output.txt");
    }

    @Test
    public void removesTypeMappingsFrom_queryGet() throws Exception {
        var json = parseJsonFromResourceName("get_query_input.txt");
        transformAndVerifyResult(json, "get_query_output.txt");
    }

    private static Map<String, Object> parseJsonFromResourceName(String resourceName) throws Exception {
        var jsonAccumulator = new JsonAccumulator();
        try (
            var resourceStream = getInputStreamForTypeMappingResource(resourceName);
            var isr = new InputStreamReader(resourceStream, StandardCharsets.UTF_8)
        ) {
            var expectedBytes = CharStreams.toString(isr).getBytes(StandardCharsets.UTF_8);
            return (Map<String, Object>) jsonAccumulator.consumeByteBufferForSingleObject(ByteBuffer.wrap(expectedBytes));
        }
    }

    private static void transformAndVerifyResult(Map<String, Object> json, String expectedValueSource)
        throws Exception {
        var jsonTransformer = getJsonTransformer();
        json = jsonTransformer.transformJson(json);
        var jsonAsStr = objectMapper.writeValueAsString(json);
        Object expectedObject = parseJsonFromResourceName(expectedValueSource);
        var expectedValue = objectMapper.writeValueAsString(expectedObject);
        Assertions.assertEquals(expectedValue, jsonAsStr);
    }

    static IJsonTransformer getJsonTransformer() {
        return new JsonTypeMappingTransformer();
    }
}
