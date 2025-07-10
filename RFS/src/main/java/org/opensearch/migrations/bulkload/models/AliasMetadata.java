package org.opensearch.migrations.bulkload.models;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AliasMetadata {
    private String alias;
    private String indexRouting;
    private String searchRouting;
    private Boolean writeIndex;
    private String filter;
    private Map<String, String> settings;

    public static AliasMetadata fromJsonWithName(String name, JsonNode node) {
        String indexRouting = node.hasNonNull("indexRouting") ? node.get("indexRouting").asText() : null;
        String searchRouting = node.hasNonNull("searchRouting") ? node.get("searchRouting").asText() : null;
        Boolean writeIndex = node.hasNonNull("writeIndex") ? node.get("writeIndex").asBoolean() : null;
        String filter = node.hasNonNull("filter") ? node.get("filter").asText() : null;

        Map<String, String> settings = new HashMap<>();
        if (node.has("settings") && node.get("settings").isObject()) {
            var it = node.get("settings").fields();
            while (it.hasNext()) {
                var entry = it.next();
                settings.put(entry.getKey(), entry.getValue().asText());
            }
        }

        return new AliasMetadata(name, indexRouting, searchRouting, writeIndex, filter, settings);
    }
}
