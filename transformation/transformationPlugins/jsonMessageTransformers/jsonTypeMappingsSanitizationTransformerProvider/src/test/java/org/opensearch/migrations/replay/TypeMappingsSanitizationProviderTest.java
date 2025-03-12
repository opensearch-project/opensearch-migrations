package org.opensearch.migrations.replay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.opensearch.migrations.testutils.JsonNormalizer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.transform.TestRequestBuilder;
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    public void testSimpleTransform() throws JsonProcessingException {
        var config = Map.of("staticMappings",
            Map.of(
                "indexa", Map.of(
                    "type1", "indexa_type1",
                    "type2", "indexa_type2"),
                "indexb", Map.of(
                    "type1", "indexb",
                    "type2", "indexb"),
                "indexc", Map.of(
                    "type2", "indexc")),
            "regexMappings", List.of(
                    Map.of(
                        "sourceIndexPattern", "(time.*)",
                        "sourceTypePattern", "(type.*)",
                        "targetIndexPattern", "$1_And_$2"),
                    Map.of(
                        "sourceIndexPattern", "(.*)",
                        "sourceTypePattern", "(.*)",
                        "targetIndexPattern", "$1") // Type Union
                ),
            "sourceProperties", Map.of(
                "version", Map.of(
                    "major", 6,
                    "minor", 8
                ))
            );
        final String TEST_INPUT_REQUEST = "{\n"
            + "  \"method\": \"PUT\",\n"
            + "  \"URI\": \"/indexa/type2/someuser\",\n"
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
            + "  \"URI\": \"/indexa_2/_doc/someuser\",\n"
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
        {
            Map<String, Object> inputMap = OBJECT_MAPPER.readValue(TEST_INPUT_REQUEST, new TypeReference<>() {});
            var resultFromNullConfig = provider.createTransformer(config).transformJson(inputMap);
            Assertions.assertEquals(
                JsonNormalizer.fromString(
                    EXPECTED.replace(
                        "/indexa_2/_doc/someuser",
                        "/indexa_type2/_doc/someuser")),
                JsonNormalizer.fromObject(resultFromNullConfig));
        }
    }

    @Test
    public void testMappingWithoutTypesAndLatestSourceInfoDoesNothing() throws Exception {
        var testString = TestRequestBuilder.makePutIndexRequest("commingled_docs", true, false);
        var fullTransformerConfig =
            Map.of("sourceProperties",
                Map.of("version",
                    Map.of("major",  (Object) 6,
                        "minor", (Object) 10)),
                "regexMappings", List.of(Map.of(
                        "sourceIndexPattern", "(.*)",
                        "sourceTypePattern", "(.*)",
                        "targetIndexPattern", "$1")));
        try (var transformer = new TypeMappingSanitizationTransformerProvider().createTransformer(fullTransformerConfig)) {
            var resultObj = transformer.transformJson(OBJECT_MAPPER.readValue(testString, LinkedHashMap.class));
            Assertions.assertEquals(JsonNormalizer.fromString(testString), JsonNormalizer.fromObject(resultObj));
        }
    }

    @Test
    public void testTypeMappingsWithSourcePropertiesWorks() throws Exception {
        var testString = TestRequestBuilder.makePutIndexRequest("commingled_docs", true, false);
        var fullTransformerConfig =
            Map.of("sourceProperties", Map.of("version",
                    Map.of("major",  (Object) 5,
                        "minor", (Object) 10)),
                "regexMappings", List.of(Map.of(
                        "sourceIndexPattern", "(.*)",
                        "sourceTypePattern", "(.*)",
                        "targetIndexPattern", "$1")));
        try (var transformer = new TypeMappingSanitizationTransformerProvider().createTransformer(fullTransformerConfig)) {
            var resultObj = transformer.transformJson(OBJECT_MAPPER.readValue(testString, LinkedHashMap.class));
            Assertions.assertEquals(JsonNormalizer.fromString(testString), JsonNormalizer.fromObject(resultObj));
        }
    }

    @Test
    public void testTypeMappingsButNoSourcePropertiesThrows() {
        Assertions.assertThrows(Exception.class, () -> new TypeMappingSanitizationTransformerProvider().createTransformer(Map.of()));
    }

}
