package org.opensearch.migrations.transform;

import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;

public class JsonJSTransformerProvider implements IJsonTransformerProvider {

    public static final String INITIALIZATION_SCRIPT = "initializationScript";
    public static final String BINDINGS_OBJECT = "bindingsObject";

    /**
     * Validates and retrieves a configuration map, ensuring it contains required keys.
     *
     * @param jsonConfig The configuration object to validate and extract from.
     * @param requiredKeys The keys that must be present in the configuration map.
     * @param optionalKeys The keys that can be optionally present in the configuration map.
     * @return The validated configuration map.
     * @throws IllegalArgumentException if the configuration is invalid or required keys are missing.
     */
    @SneakyThrows
    protected Map<String, Object> validateAndExtractConfig(Object jsonConfig, String[] requiredKeys, String[] optionalKeys) {
        if (jsonConfig == null || (jsonConfig instanceof String && ((String) jsonConfig).isEmpty())) {
            throw new IllegalArgumentException("Configuration must not be null or empty.");
        } else if (!(jsonConfig instanceof Map)) {
            throw new IllegalArgumentException(getConfigUsageStr(requiredKeys));
        }

        var config = (Map<String, Object>) jsonConfig;
        for (String key : requiredKeys) {
            if (!config.containsKey(key)) {
                throw new IllegalArgumentException("Configuration missing required key: " + key);
            }
        }
        // Optional keys are not strictly enforced
        return config;
    }

    /**
     * Generates a usage string for configuration based on required keys.
     *
     * @param requiredKeys The keys that should be present in the configuration.
     * @return A string describing the expected configuration format.
     */
    protected String getConfigUsageStr(String... requiredKeys) {
        return this.getClass().getName() + " expects the incoming configuration to be a Map<String, Object>, " +
                "with required keys: " + String.join(", ", requiredKeys) + ".";
    }

    @SneakyThrows
    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        var config = validateAndExtractConfig(jsonConfig, new String[]{INITIALIZATION_SCRIPT}, new String[]{BINDINGS_OBJECT});

        String script = (String) config.get(INITIALIZATION_SCRIPT);
        Object bindingsOutput;
        ObjectMapper objectMapper = new ObjectMapper();
        try {
            String bindingsMapString = (String) config.get(BINDINGS_OBJECT);
            bindingsOutput = objectMapper.readValue(bindingsMapString, new TypeReference<>() {});
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse the bindings map", e);
        }

        if (script == null) {
            throw new IllegalArgumentException("'script' must be provided.");
        }

        return new JavascriptTransformer(script, bindingsOutput);
    }
}
