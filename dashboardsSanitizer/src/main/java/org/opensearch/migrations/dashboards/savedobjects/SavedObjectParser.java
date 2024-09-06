package org.opensearch.migrations.dashboards.savedobjects;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Slf4j
@NoArgsConstructor
public class SavedObjectParser {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private Map<String, Class<? extends SavedObject>> specialSavedObjectTypes = new HashMap<>() {{
        put("dashboard", Dashboard.class);
    }};

    public SavedObject load(String jsonString) throws JsonProcessingException {
        if (jsonString == null) {
            throw new IllegalArgumentException("Input string is null!");
        }

        final JsonNode json = objectMapper.readTree(jsonString);

        if (json.has("exportedCount")) {
            log.debug("Skipping the exported summary line.");
            return null;
        }

        final String dataType = Optional.ofNullable(json.get("type"))
            .orElseThrow(() -> new IllegalArgumentException("Input string doesn't contain 'type' attribute!"))
            .asText();

        if (dataType.isEmpty()) {
            throw new IllegalArgumentException("The 'type' attribute is empty!");
        }

        final Class<? extends SavedObject> savedObjectClass = specialSavedObjectTypes.getOrDefault(dataType, SavedObject.class);
        try {
            return savedObjectClass.getConstructor(ObjectNode.class).newInstance(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Error creating instance of " + savedObjectClass.getSimpleName(), e);
        }
    }
}