package org.opensearch.migrations.transform;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

@Slf4j
class JsonTransformerTest {
    public static final String DUMMY_HOSTNAME_TEST_STRING = "THIS_IS_A_TEST_STRING_THAT_ONLY_EXISTS_IN_ONE_PLACE";
    ObjectMapper mapper = new ObjectMapper();

    public JsonTransformerTest() {
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);
    }

    private Map<String, Object> parseStringAsJson(String jsonStr) throws JsonProcessingException {
        return mapper.readValue(jsonStr, JsonJoltTransformBuilder.TYPE_REFERENCE_FOR_MAP_TYPE);
    }

    @SneakyThrows
    private Map<String, Object> parseSampleRequestFromResource(String path) {
        try (InputStream inputStream = JsonJoltTransformBuilder.class.getResourceAsStream("/requests/"+path)) {
            return mapper.readValue(inputStream, JsonJoltTransformBuilder.TYPE_REFERENCE_FOR_MAP_TYPE);
        }
    }

    private String emitJson(Object transformedDocument) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true); //optional
        return mapper.writeValueAsString(transformedDocument);
    }

    @Test
    public void testSimpleTransform() throws JsonProcessingException {
        final String TEST_DOCUMENT = "{\"Hello\":\"world\"}";
        var documentJson = parseStringAsJson(TEST_DOCUMENT);
        var transformer = JsonJoltTransformer.newBuilder()
                .addCannedOperation(JsonJoltTransformBuilder.CANNED_OPERATION.PASS_THRU)
                .build();
        var transformedDocument = transformer.transformJson(documentJson);
        var finalOutputStr = emitJson(transformedDocument);

        Assertions.assertEquals(TEST_DOCUMENT, finalOutputStr);
    }

    @Test
    public void testHttpTransform() throws IOException {
        var testResourceName = "parsed/post_formUrlEncoded_withFixedLength.json";
        final var documentJson = parseSampleRequestFromResource(testResourceName);
        var transformer = JsonJoltTransformer.newBuilder()
                .addHostSwitchOperation(DUMMY_HOSTNAME_TEST_STRING)
                .build();
        var transformedDocument = transformer.transformJson(documentJson);
        String transformedJsonOutputStr = emitJson(transformedDocument);
        log.atError().setMessage(()->"transformed json document: "+transformedJsonOutputStr).log();
        Assertions.assertTrue(transformedJsonOutputStr.contains(DUMMY_HOSTNAME_TEST_STRING));
    }

}