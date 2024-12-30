package org.opensearch.migrations.transform;

import java.util.Map;
import java.util.stream.Collectors;

import org.opensearch.migrations.testutils.CloseableLogSetup;
import org.opensearch.migrations.testutils.JsonNormalizer;
import org.opensearch.migrations.transform.jinjava.JinjavaConfig;
import org.opensearch.migrations.transform.jinjava.LogFunction;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;


@Slf4j
class JinjavaTransformerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final String INDEX_TYPE_MAPPING_SAMPLE_TEMPLATE = "" +
        "{# First, parse the URI to check if it matches the pattern we want to transform #}\n" +
        "{% set uri_parts = request.uri.split('/') %}\n" +
        "{% set is_type_request = uri_parts | length == 2 %}\n" +
        "{% set is_doc_request = uri_parts | length == 3 %}\n" +
        "\n" +
        "{# If this is a document request, check if we need to transform it based on mapping #}\n" +
        "{% if is_doc_request and uri_parts[0] in index_mappings and uri_parts[1] in index_mappings[uri_parts[0]] %}\n" +
        "  {# This is a document request that needs transformation #}\n" +
        "  {\n" +
        "    \"verb\": \"{{ request.verb }}\",\n" +
        "    \"uri\": \"{{ index_mappings[uri_parts[0]][uri_parts[1]] }}/_doc/{{ uri_parts[2] }}\",\n" +
        "    \"body\": {{ request.body | tojson }}\n" +
        "  }\n" +
        "{% elif is_type_request and uri_parts[0] in index_mappings %}\n" +
        "  {# This is an index creation request that needs transformation #}\n" +
        "  {\n" +
        "    \"verb\": \"{{ request.verb }}\",\n" +
        "    \"uri\": \"{{ index_mappings[uri_parts[0]][uri_parts[1]] }}\",\n" +
        "    \"body\": {\n" +
        "      \"mappings\": {\n" +
        "        \"properties\": {\n" +
        "          \"type\": {\n" +
        "            \"type\": \"keyword\"\n" +
        "          }\n" +
        "          {%- for type_name, type_props in request.body.mappings.items() %}\n" +
        "            {%- for prop_name, prop_def in type_props.properties.items() %}\n" +
        "              ,\n" +
        "              \"{{ prop_name }}\": {{ prop_def | tojson }}\n" +
        "            {%- endfor %}\n" +
        "          {%- endfor %}\n" +
        "        }\n" +
        "      }\n" +
        "    }\n" +
        "  }\n" +
        "{% else %}\n" +
        "  {# Pass through any requests that don't match our transformation patterns #}\n" +
        "  {{ request | tojson }}\n" +
        "{% endif %}";

    @Test
    public void testTypeMappingSample() throws Exception {
        var testString =
        "{\n" +
            "  \"verb\": \"PUT\",\n" +
            "  \"uri\": \"indexA/type2/someuser\",\n" +
            "  \"body\": {\n" +
            "    \"name\": \"Some User\",\n" +
            "    \"user_name\": \"user\",\n" +
            "    \"email\": \"user@example.com\"\n" +
            "  }\n" +
            "}";
        var indexMappings = Map.of(
            "indexA", Map.of(
                "type1", "indexA_1",
                "type2", "indexA_2"),
            "indexB", Map.of(
                "type1", "indexB",
                "type2", "indexB"),
            "indexC", Map.of(
                "type2", "indexC"));
        var indexTypeMappingRewriter = new JinjavaTransformer(INDEX_TYPE_MAPPING_SAMPLE_TEMPLATE,
            request -> Map.of(
                "index_mappings", indexMappings,
                "request", request),
            new JinjavaConfig(null,
                Map.of("hello", "{%- macro hello() -%} hi {%- endmacro -%}\n")));

        Object resultObj = indexTypeMappingRewriter.transformJson(OBJECT_MAPPER.readValue(testString, Map.class));
        Assertions.assertEquals(JsonNormalizer.fromString(testString.replace("indexA/type2/", "indexA_2/_doc/")),
            JsonNormalizer.fromObject(resultObj));
    }

    @Test
    public void testCustomScript() throws Exception {
        var indexTypeMappingRewriter = new JinjavaTransformer("" +
            "{%- include \"hello\" -%}" +
            "{{invoke_macro('hello')}}",
            request -> Map.of("request", request),
            new JinjavaConfig(null,
                Map.of("hello", "{%- macro hello() -%}{\"hi\": \"world\"}{%- endmacro -%}\n")));

        Object resultObj = indexTypeMappingRewriter.transformJson(Map.of());
        var resultStr = OBJECT_MAPPER.writeValueAsString(resultObj);
        Assertions.assertEquals("{\"hi\":\"world\"}", resultStr);
    }

    @Test
    public void debugLoggingWorks() throws Exception {
        try (var closeableLogSetup = new CloseableLogSetup(LogFunction.class.getName())) {
            final String FIRST_LOG_VAL = "LOGGED_VALUE=16";
            final String SECOND_LOG_VAL = "next one";
            final String THIRD_LOG_VAL = "LAST";

            var indexTypeMappingRewriter = new JinjavaTransformer("" +
                "{{ log_value_and_return('ERROR', log_value_and_return('ERROR', '" + FIRST_LOG_VAL + "', '" + SECOND_LOG_VAL + "'), '') }}" +
                "{{ log_value('ERROR', '" + THIRD_LOG_VAL + "') }} " +
                "{}",
                request -> Map.of("request", request),
                new JinjavaConfig(null,
                    Map.of("hello", "{%- macro hello() -%}{\"hi\": \"world\"}{%- endmacro -%}\n")));

            Object resultObj = indexTypeMappingRewriter.transformJson(Map.of());
            var resultStr = OBJECT_MAPPER.writeValueAsString(resultObj);
            Assertions.assertEquals("{}", resultStr);

            var logEvents = closeableLogSetup.getLogEvents();
            Assertions.assertEquals(String.join("\n", new String[]{FIRST_LOG_VAL, SECOND_LOG_VAL, THIRD_LOG_VAL}),
                logEvents.stream().collect(Collectors.joining("\n")));
        }
    }
}
