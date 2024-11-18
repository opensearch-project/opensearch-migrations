package org.opensearch.migrations.transform;

import java.util.Map;
import java.util.function.Function;

public class TypeMappingsSanitizationTransformer extends JinjavaTransformer {

    private final static String template = "" +
        // First, parse the URI to check if it matches the pattern we want to transform
        "{% set uri_parts = request.URI.split('/') | reject('equalto', '') | list %}\n" +
        "\n" +
        // If this is a document request, check if we need to transform it based on mapping
        "{% if uri_parts | length == 3 and uri_parts[0] in index_mappings and uri_parts[1] in index_mappings[uri_parts[0]] %}\n" +
        //  This is a document request that needs transformation
        "  {\n" +
        "    \"method\": \"{{ request.method }}\",\n" +
        "    \"URI\": \"/{{ index_mappings[uri_parts[0]][uri_parts[1]] }}/_doc/{{ uri_parts[2] }}\",\n" +
        "    \"headers\": {{ request.headers | tojson }},\n" +
        "    \"payload\": {{ request.payload | tojson }}\n" +
        "  }\n" +
        "{% elif uri_parts | length == 2 and uri_parts[0] in index_mappings %}\n" +
        //  This is an index creation request that needs transformation
        "  {\n" +
        "    \"method\": \"{{ request.method }}\",\n" +
        "    \"URI\": \"{{ index_mappings[uri_parts[0]][uri_parts[1]] }}\",\n" +
        "    \"payload\": {\n" +
        "      \"inlinedJsonBody\": {\n" +
        "        \"mappings\": {\n" +
        "          \"properties\": {\n" +
        "            \"type\": {\n" +
        "              \"type\": \"keyword\"\n" +
        "            }\n" +
        "            {%- for type_name, type_props in request.body.mappings.items() %}\n" +
        "              {%- for prop_name, prop_def in type_props.properties.items() %}\n" +
        "                ,\n" +
        "                \"{{ prop_name }}\": {{ prop_def | tojson }}\n" +
        "              {%- endfor %}\n" +
        "            {%- endfor %}\n" +
        "          }\n" +
        "        }\n" +
        "      }\n" +
        "    }\n" +
        "  }\n" +
        "{% else %}\n" +
        // Pass through
        " { \"urilen\": {{uri_parts | length}} }" +
        //"  {{ request | tojson }}\n" +
        "{% endif %}";

    public TypeMappingsSanitizationTransformer(Map<String, Map<String, String>> indexMappings) {
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
