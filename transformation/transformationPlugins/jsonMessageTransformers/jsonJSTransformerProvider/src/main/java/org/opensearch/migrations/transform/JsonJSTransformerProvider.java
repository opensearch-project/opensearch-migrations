package org.opensearch.migrations.transform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;

public class JsonJSTransformerProvider implements IJsonTransformerProvider {

    public static final String SCRIPT_FILE_KEY = "initializationScriptFile";
    public static final String INLINE_SCRIPT_KEY = "initializationScript";
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
                throw new IllegalArgumentException("Configuration missing required key: " + key + ". "
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
            "with keys: " + INLINE_SCRIPT_KEY + " or " + SCRIPT_FILE_KEY + ", " + BINDINGS_OBJECT + ".\n" +
            SCRIPT_FILE_KEY + " is a string pointing to a full file path to a JavaScript file to load. \n" +
            INLINE_SCRIPT_KEY + " is a string consisting of Javascript which may define functions and ends in an evaluation that returns" +
            " a main function which takes in the " + BINDINGS_OBJECT + " and returns a transform function which takes in a json object and returns the transformed object.\n" +
            BINDINGS_OBJECT + " is a value which can be deserialized with Jackson ObjectMapper into a Map, List, Array," +
            " or primitive type/wrapper and when passed as an argument into " +
            "the function returned by the " + INLINE_SCRIPT_KEY + " will give the transform function.";
    }

    @SneakyThrows
    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        var exclusiveScriptParameters = List.of(SCRIPT_FILE_KEY, INLINE_SCRIPT_KEY)
            .stream()
            .map(key -> "\"" + key + "\"")
            .collect(Collectors.joining(","));
        var requiredKeys = new String[]{BINDINGS_OBJECT};
        var config = validateAndExtractConfig(jsonConfig, requiredKeys);

        String script = null;

        var scriptFile = (String) config.getOrDefault(SCRIPT_FILE_KEY, null);
        if (scriptFile != null) {
            try {
                script = Files.readString(Path.of(scriptFile));
            } catch (IOException ioe) {
                throw new IllegalArgumentException("Failed to load script file '" + scriptFile + "'. " + getConfigUsageStr(), ioe);
            }
        }

        var inlineScript = (String) config.getOrDefault(INLINE_SCRIPT_KEY, null);
        if (inlineScript != null) {
            if (scriptFile != null) {
                throw new IllegalArgumentException("Unable to use both parameters at the same time, {" + exclusiveScriptParameters + "}. " + getConfigUsageStr());
            }
            script = inlineScript;
        }

        Object bindingsObject;
        var objectMapper = new ObjectMapper();
        try {
            String bindingsObjectString = (String) config.get(BINDINGS_OBJECT);
            bindingsObject = objectMapper.readValue(bindingsObjectString, new TypeReference<>() {});
        } catch (ClassCastException | JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse the bindingsObject. " + getConfigUsageStr(), e);
        }

        if (script == null) {
            throw new IllegalArgumentException(" One of {" + exclusiveScriptParameters + "} must be provided. " + getConfigUsageStr());
        }

        return new JavascriptTransformer(script, bindingsObject);
    }
}
