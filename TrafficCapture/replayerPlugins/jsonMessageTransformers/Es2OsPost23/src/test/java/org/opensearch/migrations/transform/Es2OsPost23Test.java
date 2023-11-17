package org.opensearch.migrations.transform;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Map;

public class Es2OsPost23Test {

    static final TypeReference<LinkedHashMap<String, Object>> TYPE_REFERENCE_FOR_MAP_TYPE = new TypeReference<>(){};

    static ObjectMapper objectMapper = new ObjectMapper();


    static InputStream getInputStreamForTypeMappingResource(String resourceName) {
        return Es2OsPost23Test.class.getResourceAsStream("/sampleJsonDocuments/typeMappings/" +
                resourceName);
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

    @Test
    public void removeTypeMappingsWhenExists() throws Exception {
        var json = parseJsonFromResourceName("put_index_settings_input_1.txt");
        transformJMESAndVerifyResult(json, "put_index_settings_output.txt");
    }

    @Test
    public void removeTypeMappingsWhenNoTypeExists() throws Exception {
        var json = parseJsonFromResourceName("put_index_settings_input_2.txt");
        transformJMESAndVerifyResult(json, "put_index_settings_output.txt");
    }

    private static Map<String,Object> parseJsonFromResourceName(String resourceName) throws Exception {
        var jsonAccumulator = new JsonAccumulator();
        try (var resourceStream = getInputStreamForTypeMappingResource(resourceName);
             var isr = new InputStreamReader(resourceStream, StandardCharsets.UTF_8)) {
            var expectedBytes = CharStreams.toString(isr).getBytes(StandardCharsets.UTF_8);
            return (Map<String,Object>) jsonAccumulator.consumeByteBuffer(ByteBuffer.wrap(expectedBytes));
        }
    }

    private static void transformJMESAndVerifyResult(Map<String,Object> json, String expectedValueSource) throws Exception{
        var jmesTransformer = getJMESTransformer();
        json = jmesTransformer.transformJson(json);
        var jsonAsStr = objectMapper.writeValueAsString(json);
        Object expectedObject = parseJsonFromResourceName(expectedValueSource);
        var expectedValue = objectMapper.writeValueAsString(expectedObject);
        Assertions.assertEquals(expectedValue, jsonAsStr);
    }

    private static void transformAndVerifyResult(Map<String,Object> json, String expectedValueSource) throws Exception {
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

    static JsonJMESPathTransformer getJMESTransformer() {
        Es2OsPost23 transformerProvider = new Es2OsPost23();
        return transformerProvider.createTransformer(null);
    }
}