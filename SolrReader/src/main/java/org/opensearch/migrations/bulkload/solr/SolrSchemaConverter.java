package org.opensearch.migrations.bulkload.solr;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Converts Solr schema field definitions to OpenSearch-compatible mappings.
 */
public final class SolrSchemaConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String OS_INTEGER = "integer";
    private static final String OS_LONG = "long";
    private static final String OS_FLOAT = "float";
    private static final String OS_DOUBLE = "double";
    private static final String OS_DATE = "date";
    private static final String OS_BOOLEAN = "boolean";
    private static final String OS_KEYWORD = "keyword";
    private static final String OS_TEXT = "text";

    private static final Map<String, String> SOLR_TO_OS_TYPE = Map.ofEntries(
        Map.entry("string", OS_KEYWORD),
        Map.entry("strings", OS_KEYWORD),
        Map.entry("text_general", OS_TEXT),
        Map.entry("text_en", OS_TEXT),
        Map.entry("text_ws", OS_TEXT),
        Map.entry("text", OS_TEXT),
        Map.entry("pint", OS_INTEGER),
        Map.entry("pints", OS_INTEGER),
        Map.entry("int", OS_INTEGER),
        Map.entry("plong", OS_LONG),
        Map.entry("plongs", OS_LONG),
        Map.entry("long", OS_LONG),
        Map.entry("pfloat", OS_FLOAT),
        Map.entry("pfloats", OS_FLOAT),
        Map.entry(OS_FLOAT, OS_FLOAT),
        Map.entry("pdouble", OS_DOUBLE),
        Map.entry("pdoubles", OS_DOUBLE),
        Map.entry(OS_DOUBLE, OS_DOUBLE),
        Map.entry("pdate", OS_DATE),
        Map.entry("pdates", OS_DATE),
        Map.entry("date", OS_DATE),
        Map.entry(OS_BOOLEAN, OS_BOOLEAN),
        Map.entry("booleans", OS_BOOLEAN),
        Map.entry("binary", "binary")
    );

    private SolrSchemaConverter() {}

    /**
     * Convert Solr schema fields array to OpenSearch mappings ObjectNode.
     */
    public static ObjectNode convertToOpenSearchMappings(JsonNode solrFields) {
        ObjectNode mappings = MAPPER.createObjectNode();
        ObjectNode properties = MAPPER.createObjectNode();

        if (solrFields != null && solrFields.isArray()) {
            for (var field : solrFields) {
                var name = field.path("name").asText();
                var type = field.path("type").asText();

                // Skip Solr internal fields
                if (name.startsWith("_") && !name.equals("id")) {
                    continue;
                }

                var osType = SOLR_TO_OS_TYPE.getOrDefault(type, OS_TEXT);
                ObjectNode fieldMapping = MAPPER.createObjectNode();
                fieldMapping.put("type", osType);
                properties.set(name, fieldMapping);
            }
        }

        mappings.set("properties", properties);
        return mappings;
    }
}
