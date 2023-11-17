package org.opensearch.migrations.transform;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;

public class Es2OsPost23 implements IJsonTransformerProvider {
/*
    @Override
    public JsonJMESPathTransformer createTransformer() {
        return new JsonJMESPathTransformer();
    }
 */
    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        ObjectMapper objectMapper = new ObjectMapper();
        String script = "{\"settings\": settings, \"mappings\": {\"properties\": mappings.*.properties | [0]}}";
        if (jsonConfig instanceof String && ((String) jsonConfig).isEmpty()) {
            //return null;
            return new JsonJMESPathTransformer(script);
        }
        Map<String, Object> jsonMap = objectMapper.convertValue(jsonConfig, Map.class);


        if (jsonMap.containsKey("mappings") && jsonMap.get("mappings") instanceof Map) {
            Map<String, Object> mappings = (Map<String, Object>) jsonMap.get("mappings");
            if (mappings.containsKey("properties")) {
                // No type - no transformation needed for "mappings" since "properties" is directly under it.
                script = "@";
            } else {
                // Type found - Transformation needed to remove the type and keep only the properties.
                script = "{\"settings\": settings, \"mappings\": {\"properties\": mappings.*.properties | [0]}}";
            }
        }
        return new JsonJMESPathTransformer(script);
    }
}
