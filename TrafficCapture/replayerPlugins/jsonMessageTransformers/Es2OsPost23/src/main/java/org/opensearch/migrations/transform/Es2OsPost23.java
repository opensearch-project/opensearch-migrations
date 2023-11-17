package org.opensearch.migrations.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Es2OsPost23 implements IJsonTransformerProvider {

    @Override
    public JsonJMESPathTransformer createTransformer(Object jsonConfig) {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> jsonMap = objectMapper.convertValue(jsonConfig, Map.class);
        String script;

        if (jsonMap.containsKey("mappings") && jsonMap.get("mappings") instanceof Map) {
            Map<String, Object> mappings = (Map<String, Object>) jsonMap.get("mappings");
            if (mappings.containsKey("properties")) {
                // No type - no transformation needed for "mappings" since "properties" is directly under it.
                script = "{\"settings\": settings, \"mappings\": mappings}";
            } else {
                // Type found - Transformation needed to remove the type and keep only the properties.
                script = "{\"settings\": settings, \"mappings\": {\"properties\": mappings.*.properties | [0]}}";
            }
        } else {
            script = "{\"settings\": settings}";
        }
        return new JsonJMESPathTransformer(script);
    }
}
