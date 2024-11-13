package org.opensearch.migrations.transform;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

public class TypeMappingSanitizerTransformer extends JinjavaTransformer {

    private final static String template = "" +
        // First, parse the URI to check if it matches the pattern we want to transform
        "{% set uri_parts = request.uri.split('/') %}\n" +
        "{% set is_type_request = uri_parts | length == 2 %}\n" +
        "{% set is_doc_request = uri_parts | length == 3 %}\n" +
        "\n" +
        // If this is a document request, check if we need to transform it based on mapping
        "{% if is_doc_request and uri_parts[0] in index_mappings and uri_parts[1] in index_mappings[uri_parts[0]] %}\n" +
        //  This is a document request that needs transformation
        "  {\n" +
        "    \"verb\": \"{{ request.verb }}\",\n" +
        "    \"uri\": \"{{ index_mappings[uri_parts[0]][uri_parts[1]] }}/_doc/{{ uri_parts[2] }}\",\n" +
        "    \"body\": {{ request.body | tojson }}\n" +
        "  }\n" +
        "{% elif is_type_request and uri_parts[0] in index_mappings %}\n" +
        //  This is an index creation request that needs transformation
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
        // Pass through
        "  {{ request | tojson }}\n" +
        "{% endif %}";

    public TypeMappingSanitizerTransformer(Map<String, Map<String, String>> indexMappings) {
        super(template, getContextWrapper(indexMappings));
    }

    private static Function<Map<String, Object>, Map<String, Object>>
    getContextWrapper(Map<String, Map<String, String>> indexMappings)
    {
        return incomingJson -> Map.of(
                "index_mappings", indexMappings,
                "request", incomingJson);
    }
}
