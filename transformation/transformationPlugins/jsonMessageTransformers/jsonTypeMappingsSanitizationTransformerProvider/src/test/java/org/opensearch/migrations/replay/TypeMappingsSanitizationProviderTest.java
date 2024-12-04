package org.opensearch.migrations.replay;

import java.util.List;
import java.util.Map;

import org.opensearch.migrations.testutils.JsonNormalizer;
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
public class TypeMappingsSanitizationProviderTest {

    ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testSimpleTransform() throws JsonProcessingException {
        var config = Map.of("staticMappings",
            Map.of(
                "indexA", Map.of(
                    "type1", "indexA_1",
                    "type2", "indexA_2"),
                "indexB", Map.of(
                    "type1", "indexB",
                    "type2", "indexB"),
                "indexC", Map.of(
                    "type2", "indexC")),
            "regexMappings", List.of(List.of("(time.*)", "(type.*)", "$1_And_$2")));
        final String TEST_INPUT_REQUEST = "{\n"
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
        final String EXPECTED = "{\n"
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

        var provider = new TypeMappingSanitizationTransformerProvider();
        Map<String, Object>  inputMap = mapper.readValue(TEST_INPUT_REQUEST, new TypeReference<>() {});
        {
            var transformedDocument = provider.createTransformer(config).transformJson(inputMap);
            Assertions.assertEquals(JsonNormalizer.fromString(EXPECTED),
                JsonNormalizer.fromObject(transformedDocument));
        }
        {
            var resultFromNullConfig = provider.createTransformer(null).transformJson(inputMap);
            Assertions.assertEquals(
                JsonNormalizer.fromString(
                    EXPECTED.replace(
                        "/indexA_2/_doc/someuser",
                        "/indexA/_doc/someuser")),
                JsonNormalizer.fromObject(resultFromNullConfig));
        }
    }
}
