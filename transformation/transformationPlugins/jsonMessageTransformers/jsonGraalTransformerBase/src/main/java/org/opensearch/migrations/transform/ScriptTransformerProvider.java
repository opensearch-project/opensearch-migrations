package org.opensearch.migrations.transform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Base class for script-based transformer providers (JavaScript, Python, etc.).
 *
 * <p>Handles the common config contract: script resolution (file, resource, inline),
 * bindings object parsing, and config validation. Subclasses implement
 * {@link #getLanguageName()} and {@link #buildTransformer} to create the
 * language-specific transformer.
 */
public abstract class ScriptTransformerProvider implements IJsonTransformerProvider {

    public static final String SCRIPT_FILE_KEY = "initializationScriptFile";
    public static final String RESOURCE_PATH_KEY = "initializationResourcePath";
    public static final String INLINE_SCRIPT_KEY = "initializationScript";
    public static final String BINDINGS_OBJECT = "bindingsObject";
    public static final String BINDINGS_OBJECT_DIRS = "bindingsObjectDirs";
    public static final String BINDINGS_OBJECT_FILES = "bindingsObjectFiles";
    private static final String PATH_KEY = "path";

    public record ResolvedScript(String source, Path sourceFile) {}

    /** Human-readable language name for error messages (e.g. "JavaScript", "Python"). */
    protected abstract String getLanguageName();

    /** Create the language-specific transformer from resolved config values. */
    protected abstract IJsonTransformer buildTransformer(
        ResolvedScript script, Object bindingsObject, Map<String, Object> config) throws IOException;

    @Override
    public IJsonTransformer createTransformer(Object jsonConfig) {
        var config = validateConfig(jsonConfig);
        try {
            var script = resolveScript(config);
            var bindings = resolveBindingsObject(config);
            return buildTransformer(script, bindings, config);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to create transformer: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> validateConfig(Object jsonConfig) {
        return validateAndExtractConfig(jsonConfig, new String[]{});
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> validateAndExtractConfig(Object jsonConfig, String[] requiredKeys) {
        if (jsonConfig == null || (jsonConfig instanceof String && ((String) jsonConfig).isEmpty())) {
            throw new IllegalArgumentException("Configuration must not be null or empty.");
        } else if (!(jsonConfig instanceof Map)) {
            throw new IllegalArgumentException(getConfigUsageStr());
        }
        var config = (Map<String, Object>) jsonConfig;
        for (String key : requiredKeys) {
            if (!config.containsKey(key)) {
                throw new IllegalArgumentException(
                    "Configuration missing required key: " + key + ". " + getConfigUsageStr()
                );
            }
        }
        return config;
    }

    protected ResolvedScript resolveScript(Map<String, Object> config) throws IOException {
        var exclusiveParams = List.of(SCRIPT_FILE_KEY, INLINE_SCRIPT_KEY, RESOURCE_PATH_KEY)
            .stream().map(k -> "\"" + k + "\"").collect(Collectors.joining(","));

        String script = null;
        Path sourceFile = null;

        var scriptFile = (String) config.getOrDefault(SCRIPT_FILE_KEY, null);
        if (scriptFile != null) {
            try {
                sourceFile = Path.of(scriptFile);
                script = Files.readString(sourceFile);
            } catch (IOException ioe) {
                throw new IllegalArgumentException(
                    "Failed to load script file '" + scriptFile + "'. " + getConfigUsageStr(), ioe);
            }
        }

        var resourceFile = (String) config.getOrDefault(RESOURCE_PATH_KEY, null);
        if (resourceFile != null) {
            if (script != null) {
                throw new IllegalArgumentException(
                    "Unable to use both parameters at the same time, {" + exclusiveParams + "}. "
                        + getConfigUsageStr());
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
                    "Unable to use both parameters at the same time, {" + exclusiveParams + "}. "
                        + getConfigUsageStr());
            }
            script = inlineScript;
        }

        if (script == null) {
            throw new IllegalArgumentException(
                "One of {" + exclusiveParams + "} must be provided. " + getConfigUsageStr());
        }
        return new ResolvedScript(script, sourceFile);
    }

    @SuppressWarnings("unchecked")
    protected Object resolveBindingsObject(Map<String, Object> config) throws IOException {
        var fileBackedBindings = readFileBackedBindings(config);
        var bindingsObject = parseBindingsObject(config);
        if (fileBackedBindings.isEmpty()) {
            return bindingsObject;
        }
        if (!(bindingsObject instanceof Map)) {
            throw new IllegalArgumentException(
                "Cannot merge " + BINDINGS_OBJECT_DIRS + " or " + BINDINGS_OBJECT_FILES
                    + " with a non-object " + BINDINGS_OBJECT + ". " + getConfigUsageStr());
        }

        /*
         * Script bindings are flattened before the script's main(context) function is
         * called. Merge order is intentionally last-writer-wins for conflicting keys:
         *
         *   1. bindingsObjectDirs, in the order supplied. Within each directory, regular
         *      files are processed by file name for deterministic output. The file name is
         *      the binding key and the file contents are the value.
         *   2. bindingsObjectFiles. The map key is the binding key and the file contents
         *      are the value.
         *   3. bindingsObject, after JSON-string parsing when needed.
         *
         * Practically, explicit bindingsObject values override mounted file-backed values,
         * and individually named files override directory-loaded values. If any file-backed
         * bindings are present, bindingsObject must be an object/map so the merge is
         * unambiguous; plain string contexts are only valid when no file-backed bindings are
         * being merged. Duplicate keys inside one JSON object are not a supported conflict
         * mechanism; once the JSON is parsed, each map has one value per key.
         */
        var mergedBindings = new LinkedHashMap<String, Object>(fileBackedBindings);
        mergedBindings.putAll((Map<String, Object>) bindingsObject);
        return mergedBindings;
    }

    protected Object parseBindingsObject(Map<String, Object> config) {
        if (!config.containsKey(BINDINGS_OBJECT) || config.get(BINDINGS_OBJECT) == null) {
            return Collections.emptyMap();
        }
        var bindingsObject = config.get(BINDINGS_OBJECT);
        if (!(bindingsObject instanceof String)) {
            return bindingsObject;
        }
        var objectMapper = new ObjectMapper();
        try {
            String bindingsObjectString = (String) bindingsObject;
            return objectMapper.readValue(bindingsObjectString, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                "Failed to parse the bindingsObject. " + getConfigUsageStr(), e);
        }
    }

    protected Map<String, Object> readFileBackedBindings(Map<String, Object> config) throws IOException {
        var resolved = new LinkedHashMap<String, Object>();
        resolved.putAll(readDirectoryValues(config.get(BINDINGS_OBJECT_DIRS)));
        resolved.putAll(readFileValues(config.get(BINDINGS_OBJECT_FILES)));
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDirectoryValues(Object directoriesConfig) throws IOException {
        if (directoriesConfig == null) {
            return Collections.emptyMap();
        }
        if (!(directoriesConfig instanceof List)) {
            throw new IllegalArgumentException(BINDINGS_OBJECT_DIRS + " must be a list.");
        }

        var result = new LinkedHashMap<String, Object>();
        for (var directoryConfig : (List<Object>) directoriesConfig) {
            var directoryPath = getRequiredPath(directoryConfig, BINDINGS_OBJECT_DIRS);
            try (var entries = Files.list(directoryPath)) {
                for (var file : entries
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                    result.put(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFileValues(Object filesConfig) throws IOException {
        if (filesConfig == null) {
            return Collections.emptyMap();
        }
        if (!(filesConfig instanceof Map)) {
            throw new IllegalArgumentException(BINDINGS_OBJECT_FILES + " must be a map.");
        }

        var result = new LinkedHashMap<String, Object>();
        for (var entry : ((Map<String, Object>) filesConfig).entrySet()) {
            var path = getRequiredPath(entry.getValue(), BINDINGS_OBJECT_FILES + "." + entry.getKey());
            result.put(entry.getKey(), Files.readString(path, StandardCharsets.UTF_8));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Path getRequiredPath(Object config, String configName) {
        if (config instanceof String) {
            return Path.of((String) config);
        }
        if (config instanceof Map) {
            var pathValue = ((Map<String, Object>) config).get(PATH_KEY);
            if (pathValue instanceof String) {
                return Path.of((String) pathValue);
            }
        }
        throw new IllegalArgumentException(configName + " must specify a " + PATH_KEY + " string.");
    }

    protected String getConfigUsageStr() {
        var lang = getLanguageName();
        return this.getClass().getName() + " expects the incoming configuration to be a Map<String, Object>, "
            + "with keys: " + INLINE_SCRIPT_KEY + " or " + SCRIPT_FILE_KEY + ", " + BINDINGS_OBJECT + ".\n"
            + SCRIPT_FILE_KEY + " is a string pointing to a file path to a " + lang + " file to load.\n"
            + RESOURCE_PATH_KEY + " is a string pointing to a resource path to a " + lang
            + " file in a jar on the classpath.\n"
            + INLINE_SCRIPT_KEY + " is a string consisting of " + lang
            + " which defines a main function that takes in the " + BINDINGS_OBJECT
            + " and returns a transform function.\n"
            + BINDINGS_OBJECT + " is optional and may be a JSON object or a string which can be deserialized with Jackson ObjectMapper.\n"
            + BINDINGS_OBJECT_FILES + " and " + BINDINGS_OBJECT_DIRS
            + " are optional file-backed binding sources resolved before the transformer is created.";
    }
}
