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
    public static final String RESOURCE_DECREASE_SHARDS_OPERATION = "decreaseShards.jolt";
    ObjectMapper mapper = new ObjectMapper();

    public JsonTransformerTest() {
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);
    }

    private Map<String, Object> parseStringAsJson(String jsonStr) throws JsonProcessingException {
        return mapper.readValue(jsonStr, JoltJsonTransformBuilder.TYPE_REFERENCE_FOR_MAP_TYPE);
    }

    @SneakyThrows
    private Map<String, Object> parseSampleRequestFromResource(String path) {
        try (InputStream inputStream = JoltJsonTransformBuilder.class.getResourceAsStream("/requests/"+path)) {
            return mapper.readValue(inputStream, JoltJsonTransformBuilder.TYPE_REFERENCE_FOR_MAP_TYPE);
        }
    }

    private String emitOrderedJson(Object transformedDocument) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true); //optional
        return mapper.writeValueAsString(transformedDocument);
    }

    @Test
    public void testSimpleTransform() throws JsonProcessingException {
        final String TEST_DOCUMENT = "{\"Hello\":\"world\"}";
        var documentJson = parseStringAsJson(TEST_DOCUMENT);
        var transformer = JoltJsonTransformer.newBuilder()
                .addCannedOperation(JoltJsonTransformBuilder.CANNED_OPERATIONS.PASS_THRU)
                .build();
        var transformedDocument = transformer.transformJson(documentJson);
        var finalOutputStr = emitOrderedJson(transformedDocument);
        log.error("final document: "+finalOutputStr);

        Assertions.assertEquals(TEST_DOCUMENT, finalOutputStr);
    }


    private static String chainSpecs(String... specs) {
        var joinedSpecs = String.join(",", specs);
        return "{\"spec\": ["+joinedSpecs+"]}";
    }

    @Test
    public void testHttpTransform() throws IOException {
        var testResourceName = "parsed/post_formUrlEncoded_withFixedLength.json";
        final var documentJson = parseSampleRequestFromResource(testResourceName);
        var transformer = JoltJsonTransformer.newBuilder()
                .addHostSwitchOperation(DUMMY_HOSTNAME_TEST_STRING)
                .build();
        var transformedDocument = transformer.transformJson(documentJson);
        String transformedJsonOutputStr = emitOrderedJson(transformedDocument);
        log.error("transformed json document: "+transformedJsonOutputStr);
        Assertions.assertTrue(transformedJsonOutputStr.contains(DUMMY_HOSTNAME_TEST_STRING));

//        var checkit = HTTP.toJSONObject("" +
//                "POST /test HTTP/1.1\n"+
//                "Host: foo.example\n" +
//                "Content-Type: application/x-www-form-urlencoded\n" +
//                "Content-Length: 27");
        //var parsedTransformedHeaders = HTTP.toJSONObject(transformedJsonOutputStr);
        //Map<String, Object> transformedHeaders = (Map<String, Object>) parsedTransformedHeaders.get("headers");
        //Assertions.assertEquals("testcluster.org", transformedHeaders.get("Host"));

        //transformedHeaders.put("Host", "sourcecluster.org");
        //Assertions.assertEquals(parseSampleRequestFromResource(testResourceName).toString(), transformedHeaders.toString());
    }

//    @Test
//    public void testThatLazyPayloadsAreLazy() throws IOException {
//        Map<String, Object> jsonRequest;
//        var httpParser = new HttpJsonTransformer();
//        try (var is = getClass().getResourceAsStream("/requests/raw/post_formUrlEncoded_withFixedLength.txt")) {
//            httpParser.acceptRawRequestBytes(is.readAllBytes());
//        }
//        jsonRequest = httpParser.asJsonDocument();
//        //var payloadJson = ((Map)jsonRequest.get("payload")).get("inlinedJsonBody");
//        var transformer = JsonTransformer.newBuilder()
//                .addHostSwitchOperation(DUMMY_HOSTNAME_TEST_STRING)
//                .build();
//        var transformedDocument = transformer.transformJson(jsonRequest);
//        log.error(transformedDocument.toString());
//    }
}