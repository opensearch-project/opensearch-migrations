package org.opensearch.migrations.replay;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.opensearch.migrations.testutils.JsonNormalizer;
import org.opensearch.migrations.testutils.WrapWithNettyLeakDetection;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;
import org.opensearch.migrations.transform.TestRequestBuilder;
import org.opensearch.migrations.transform.TypeMappingSanitizationTransformerProvider;
import org.opensearch.migrations.transform.jinjava.ThrowTag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hubspot.jinjava.interpret.FatalTemplateErrorsException;
import lombok.NonNull;
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
                    "type1", "indexa_1",
                    "type2", "indexa_2"),
                "indexb", Map.of(
                    "type1", "indexb",
                    "type2", "indexb"),
                "indexc", Map.of(
                    "type2", "indexc")),
            "regexMappings", List.of(List.of("(time.*)", "(type.*)", "\\1_And_\\2")));
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
        Map<String, Object> inputMap = OBJECT_MAPPER.readValue(TEST_INPUT_REQUEST, new TypeReference<>() {
        });
        {
            Object transformedDocument = provider.createTransformer(config).transformJson(inputMap);
            Assertions.assertEquals(JsonNormalizer.fromString(EXPECTED),
                JsonNormalizer.fromObject(transformedDocument));
        }
        {
            Object resultFromNullConfig = provider.createTransformer(null).transformJson(inputMap);
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
                        "minor", (Object) 10)));
        var transformer = new TypeMappingSanitizationTransformerProvider().createTransformer(fullTransformerConfig);
        Object resultObj = transformer.transformJson(OBJECT_MAPPER.readValue(testString, LinkedHashMap.class));
        Assertions.assertEquals(JsonNormalizer.fromString(testString), JsonNormalizer.fromObject(resultObj));
    }

    @Test
    public void testTypeMappingsWithSourcePropertiesWorks() throws Exception {
        var testString = TestRequestBuilder.makePutIndexRequest("commingled_docs", true, false);
        var fullTransformerConfig =
            Map.of("sourceProperties", Map.of("version",
                    Map.of("major",  (Object) 5,
                        "minor", (Object) 10)),
                "regex_index_mappings", List.of(List.of("", "", "")));
        var transformer = new TypeMappingSanitizationTransformerProvider().createTransformer(fullTransformerConfig);
        Object resultObj = transformer.transformJson(OBJECT_MAPPER.readValue(testString, LinkedHashMap.class));
        Assertions.assertEquals(JsonNormalizer.fromString(testString), JsonNormalizer.fromObject(resultObj));
    }

    @Test
    public void testMappingsButNoSourcePropertiesThrows() throws Exception {
        var testString = makeCreateIndexRequestWithoutTypes();
        var noopString = "{\n" +
            "  \"URI\" : \"/\",\n" +
            "  \"method\" : \"GET\"\n" +
            "}";
        var transformer = new TypeMappingSanitizationTransformerProvider().createTransformer(null);
        var thrownException =
            Assertions.assertThrows(FatalTemplateErrorsException.class, () ->
            transformer.transformJson(OBJECT_MAPPER.readValue(testString, LinkedHashMap.class)));
        Assertions.assertNotNull(
            findCausalException(thrownException.getErrors().iterator().next().getException(),
                e->e==null || e instanceof ThrowTag.JinjavaThrowTagException));
    }

    private static @NonNull String makeCreateIndexRequestWithoutTypes() {
        return "{\n" +
            "  \"" + JsonKeysForHttpMessage.METHOD_KEY + "\": \"PUT\",\n" +
            "  \"" + JsonKeysForHttpMessage.URI_KEY + "\": \"/geonames\",\n" +
            "  \"" + JsonKeysForHttpMessage.PROTOCOL_KEY + "\": \"HTTP/1.1\"," +
            "  \"" + JsonKeysForHttpMessage.HEADERS_KEY + "\": {\n" +
            "    \"Host\": \"capture-proxy:9200\"\n" +
            "  }," +
            "  \"" + JsonKeysForHttpMessage.PAYLOAD_KEY + "\": {\n" +
            "    \"" + JsonKeysForHttpMessage.INLINED_JSON_BODY_DOCUMENT_KEY + "\": {\n" +
            "      \"settings\": {\n" +
            "        \"index\": {\n" +
            "          \"number_of_shards\": 3,  \n" +
            "          \"number_of_replicas\": 2 \n" +
            "        }\n" +
            "      }," +
            "      \"mappings\": {" +
            "        \"properties\": {\n" +
            "          \"field1\": { \"type\": \"text\" }\n" +
            "        }" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}";
    }

    public static Throwable findCausalException(Throwable t, Predicate<Throwable> p) {
        while (!p.test(t)) {
            t = t.getCause();
        }
        return t;
    }
}
