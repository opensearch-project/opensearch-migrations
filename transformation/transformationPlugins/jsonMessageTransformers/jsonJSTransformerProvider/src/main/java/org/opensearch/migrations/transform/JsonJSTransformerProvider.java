package org.opensearch.migrations.transform;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;

public class JsonJSTransformerProvider implements IJsonTransformerProvider {

    public static final String INITIALIZATION_SCRIPT_KEY = "initializationScript";
    public static final String BINDINGS_OBJECT = "bindingsObject";

    /**
     * Validates and retrieves a configuration map, ensuring it contains required keys.
     *
     * @param jsonConfig The configuration object to validate and extract from.
     * @param requiredKeys The keys that must be present in the configuration map.
     * @return The validated configuration map.
     * @throws IllegalArgumentException if the configuration is invalid or required keys are missing.
     */
    @SneakyThrows
    protected Map<String, Object> validateAndExtractConfig(Object jsonConfig, String[] requiredKeys) {
        if (jsonConfig == null || (jsonConfig instanceof String && ((String) jsonConfig).isEmpty())) {
            throw new IllegalArgumentException("Configuration must not be null or empty.");
        } else if (!(jsonConfig instanceof Map)) {
            throw new IllegalArgumentException(getConfigUsageStr());
        }

        var config = (Map<String, Object>) jsonConfig;
        for (String key : requiredKeys) {
            if (!config.containsKey(key)) {
                throw new IllegalArgumentException("Configuration missing required key: " + key + "."
                + getConfigUsageStr());
            }
        }
        // Optional keys are not strictly enforced
        return config;
    }

    /**
     * Generates a usage string for configuration.
     *
     * @return A string describing the expected configuration format.
     */
    protected String getConfigUsageStr() {
        return this.getClass().getName() + " expects the incoming configuration to be a Map<String, Object>, " +
            "with keys: " + String.join("', '", new String[]{INITIALIZATION_SCRIPT_KEY, BINDINGS_OBJECT}) + "." +
            INITIALIZATION_SCRIPT_KEY + " is a string consisting of Javascript which may define functions and ends in an evaluation that returns" +
            " a main function which takes in the " + BINDINGS_OBJECT + " and returns a transform function which takes in a json object and returns the transformed object." +
            BINDINGS_OBJECT + " is a value which can be deserialized with Jackson ObjectMapper into a Map, List, Array," +
            " or primitive type/wrapper and when passed as an argument into " +
            "the function returned by the " + INITIALIZATION_SCRIPT_KEY + " will give the transform function.";
    }

    @SneakyThrows
    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        var requiredKeys = new String[]{INITIALIZATION_SCRIPT_KEY, BINDINGS_OBJECT};
        var config = validateAndExtractConfig(jsonConfig, requiredKeys);

        String script = (String) config.get(INITIALIZATION_SCRIPT_KEY);
        Object bindingsObject;
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            String bindingsObjectString = (String) config.get(BINDINGS_OBJECT);
            bindingsObject = objectMapper.readValue(bindingsObjectString, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse the bindingsObject." + getConfigUsageStr(), e);
        }

        if (script == null) {
            throw new IllegalArgumentException(INITIALIZATION_SCRIPT_KEY + " must be provided." + getConfigUsageStr());
        }

        return new JavascriptTransformer(script, bindingsObject);
    }
}
