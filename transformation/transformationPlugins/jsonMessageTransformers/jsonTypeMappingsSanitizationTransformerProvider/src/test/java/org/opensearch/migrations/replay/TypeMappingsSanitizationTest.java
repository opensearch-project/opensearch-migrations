package org.opensearch.migrations.replay;

import java.util.Map;

import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.transform.TypeMappingSanitizationTransformerProvider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

@Slf4j
@WrapWithNettyLeakDetection(disableLeakChecks = true)
public class TypeMappingsSanitizationTest {

    static final String TEST_INPUT_REQUEST = "{\n"
        + "  \"method\": \"PUT\",\n"
        + "  \"URI\": \"/indexA/type2/someuser\",\n"
        + "  \"headers\": {\n"
        + "    \"host\": \"127.0.0.1\"\n"
        + "  },\n"
        + "  \"payload\": {\n"
        + "    \"inlinedJsonBody\": {\n"
        + "      \"name\": \"Some User\",\n"
        + "      \"user_name\": \"user\",\n"
        + "      \"email\": \"user@example.com\"\n"
        + "    }\n"
        + "  }\n"
        + "}\n";


    ObjectMapper mapper = new ObjectMapper();

    static String normalize(ObjectMapper mapper, String input) throws JsonProcessingException {
        return mapper.writeValueAsString(mapper.readTree(input));
    }

    static String emitJson(ObjectMapper mapper, Object transformedDocument) throws JsonProcessingException {
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true); // optional
        return mapper.writeValueAsString(transformedDocument);
    }

    @Test
    public void testSimpleTransform() throws JsonProcessingException {
        var config = Map.of(
            "indexA", Map.of(
                "type1", "indexA_1",
                "type2", "indexA_2"),
            "indexB", Map.of(
                "type1", "indexB",
                "type2", "indexB"),
            "indexC", Map.of(
                "type2", "indexC"));;
        var transformer = new TypeMappingSanitizationTransformerProvider().createTransformer(config);
        var transformedDocument =
            transformer.transformJson(mapper.readValue(TEST_INPUT_REQUEST, new TypeReference<>(){}));
        var outputStr = emitJson(mapper, transformedDocument);

        log.atInfo().setMessage("output={}").addArgument(outputStr).log();
        final String TEST_OUTPUT_REQUEST = "{\n"
                + "  \"method\": \"PUT\",\n"
                + "  \"URI\": \"/indexA_2/_doc/someuser\",\n"
                + "  \"headers\": {\n"
                + "    \"host\": \"127.0.0.1\"\n"
                + "  },\n"
                + "  \"payload\": {\n"
                + "    \"inlinedJsonBody\": {\n"
                + "      \"name\": \"Some User\",\n"
                + "      \"user_name\": \"user\",\n"
                + "      \"email\": \"user@example.com\"\n"
                + "    }\n"
                + "  }\n"
                + "}\n";

        Assertions.assertEquals(normalize(mapper, TEST_OUTPUT_REQUEST), normalize(mapper, outputStr));
    }
}
