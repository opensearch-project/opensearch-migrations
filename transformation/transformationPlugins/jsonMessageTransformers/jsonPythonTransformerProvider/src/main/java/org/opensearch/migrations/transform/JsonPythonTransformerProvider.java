package org.opensearch.migrations.transform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;

public class JsonPythonTransformerProvider implements IJsonTransformerProvider {

    public static final String SCRIPT_FILE_KEY = "initializationScriptFile";
    public static final String RESOURCE_PATH_KEY = "initializationResourcePath";
    public static final String INLINE_SCRIPT_KEY = "initializationScript";
    public static final String BINDINGS_OBJECT = "bindingsObject";

    @SneakyThrows
    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        var config = validateAndExtractConfig(jsonConfig);
        var script = resolveScript(config);
        var bindingsObject = parseBindingsObject(config);
        return new PythonTransformer(script, bindingsObject);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> validateAndExtractConfig(Object jsonConfig) {
        if (jsonConfig == null || (jsonConfig instanceof String && ((String) jsonConfig).isEmpty())) {
            throw new IllegalArgumentException("Configuration must not be null or empty.");
        } else if (!(jsonConfig instanceof Map)) {
            throw new IllegalArgumentException(getConfigUsageStr());
        }
        var config = (Map<String, Object>) jsonConfig;
        if (!config.containsKey(BINDINGS_OBJECT)) {
            throw new IllegalArgumentException(
                "Configuration missing required key: " + BINDINGS_OBJECT + ". " + getConfigUsageStr()
            );
        }
        return config;
    }

    private String resolveScript(Map<String, Object> config) throws IOException {
        var exclusiveScriptParameters = List.of(SCRIPT_FILE_KEY, INLINE_SCRIPT_KEY, RESOURCE_PATH_KEY)
            .stream()
            .map(key -> "\"" + key + "\"")
            .collect(Collectors.joining(","));

        String script = null;

        var scriptFile = (String) config.getOrDefault(SCRIPT_FILE_KEY, null);
        if (scriptFile != null) {
            try {
                script = Files.readString(Path.of(scriptFile));
            } catch (IOException ioe) {
                throw new IllegalArgumentException(
                    "Failed to load script file '" + scriptFile + "'. " + getConfigUsageStr(), ioe
                );
            }
        }

        String resourceFile = (String) config.getOrDefault(RESOURCE_PATH_KEY, null);
        if (resourceFile != null) {
            if (script != null) {
                throw new IllegalArgumentException(
                    "Unable to use both parameters at the same time, {" + exclusiveScriptParameters + "}. "
                        + getConfigUsageStr()
                );
            }
            try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceFile)) {
                if (is == null) {
                    throw new IllegalArgumentException("Resource not found: " + resourceFile);
                }
                script = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        var inlineScript = (String) config.getOrDefault(INLINE_SCRIPT_KEY, null);
        if (inlineScript != null) {
            if (script != null) {
                throw new IllegalArgumentException(
                    "Unable to use both parameters at the same time, {" + exclusiveScriptParameters + "}. "
                        + getConfigUsageStr()
                );
            }
            script = inlineScript;
        }

        if (script == null) {
            throw new IllegalArgumentException(
                "One of {" + exclusiveScriptParameters + "} must be provided. " + getConfigUsageStr()
            );
        }
        return script;
    }

    private Object parseBindingsObject(Map<String, Object> config) {
        var objectMapper = new ObjectMapper();
        try {
            String bindingsObjectString = (String) config.get(BINDINGS_OBJECT);
            return objectMapper.readValue(bindingsObjectString, new TypeReference<>() {});
        } catch (ClassCastException | JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to parse the bindingsObject. " + getConfigUsageStr(), e);
        }
    }

    private String getConfigUsageStr() {
        return this.getClass().getName() + " expects the incoming configuration to be a Map<String, Object>, "
            + "with keys: " + INLINE_SCRIPT_KEY + " or " + SCRIPT_FILE_KEY + ", " + BINDINGS_OBJECT + ".\n"
            + SCRIPT_FILE_KEY + " is a string pointing to a full file path to a Python file to load. \n"
            + RESOURCE_PATH_KEY
            + " is a string pointing to a resource path to a Python file in a jar on the classpath. \n"
            + INLINE_SCRIPT_KEY
            + " is a string consisting of Python which defines a main function that takes in the "
            + BINDINGS_OBJECT
            + " and returns a transform function which takes in a json object and returns the transformed object.\n"
            + BINDINGS_OBJECT
            + " is a value which can be deserialized with Jackson ObjectMapper into a Map, List, Array,"
            + " or primitive type/wrapper.";
    }
}
