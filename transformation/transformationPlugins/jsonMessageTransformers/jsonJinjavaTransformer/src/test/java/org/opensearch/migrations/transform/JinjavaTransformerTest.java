package org.opensearch.migrations.transform;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

class JinjavaTransformerTest {

    private final static String template = "" +
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

    private static JinjavaTransformer indexTypeMappingRewriter;
    @BeforeAll
    static void initialize() {
        var indexMappings = Map.of(
            "indexA", Map.of(
                "type1", "indexA_1",
                "type2", "indexA_2"),
            "indexB", Map.of(
                "type1", "indexB",
                "type2", "indexB"),
            "indexC", Map.of(
                "type2", "indexC"));
        indexTypeMappingRewriter = new JinjavaTransformer(template, request ->
            Map.of("index_mappings", indexMappings,
                "request", request));
    }

    @Test
    public void test() throws Exception {
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
        var objMapper = new ObjectMapper();
        var resultObj = indexTypeMappingRewriter.transformJson(objMapper.readValue(testString, Map.class));
        var resultStr = objMapper.writeValueAsString(resultObj);
        System.out.println("resultStr = " + resultStr);
    }
}