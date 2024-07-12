package org.opensearch.migrations.replay;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.transform.JsonJoltTransformBuilder;
import org.opensearch.migrations.transform.JsonJoltTransformer;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@WrapWithNettyLeakDetection(disableLeakChecks = true)
class JsonTransformerTest {
    public static final String DUMMY_HOSTNAME_TEST_STRING = "THIS_IS_A_TEST_STRING_THAT_ONLY_EXISTS_IN_ONE_PLACE";
    ObjectMapper mapper = new ObjectMapper();
    static final TypeReference<LinkedHashMap<String, Object>> TYPE_REFERENCE_FOR_MAP_TYPE = new TypeReference<>() {
    };

    public JsonTransformerTest() {
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);
    }

    private Map<String, Object> parseStringAsJson(String jsonStr) throws JsonProcessingException {
        return mapper.readValue(jsonStr, TYPE_REFERENCE_FOR_MAP_TYPE);
    }

    @SneakyThrows
    private Map<String, Object> parseSampleRequestFromResource(String path) {
        try (InputStream inputStream = JsonTransformerTest.class.getResourceAsStream("/requests/" + path)) {
            return mapper.readValue(inputStream, TYPE_REFERENCE_FOR_MAP_TYPE);
        }
    }

    private String emitJson(Object transformedDocument) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true); // optional
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
        var transformer = JsonJoltTransformer.newBuilder().addHostSwitchOperation(DUMMY_HOSTNAME_TEST_STRING).build();
        var transformedDocument = transformer.transformJson(documentJson);
        String transformedJsonOutputStr = emitJson(transformedDocument);
        log.info("transformed json document: " + transformedJsonOutputStr);
        Assertions.assertTrue(transformedJsonOutputStr.contains(DUMMY_HOSTNAME_TEST_STRING));
    }
}
