package org.opensearch.migrations.transform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TransformationLoader {
    public static final String WRONG_JSON_STRUCTURE_MESSAGE =
        "Must specify the top-level configuration list with a sequence of "
            + "maps that have only one key each, where the key is the name of the transformer to be configured.";
    public static final Pattern CLASS_NAME_PATTERN = Pattern.compile("^[^{}]*$");
    public static final String PROVIDER_CONFIG_DIRS_KEY = "providerConfigDirs";
    public static final String PROVIDER_CONFIG_FILES_KEY = "providerConfigFiles";
    private static final String PATH_KEY = "path";
    private final List<IJsonTransformerProvider> providers;
    ObjectMapper objMapper = new ObjectMapper();

    public TransformationLoader() {
        ServiceLoader<IJsonTransformerProvider> transformerProviders = ServiceLoader.load(
            IJsonTransformerProvider.class
        );
        var inProgressProviders = new ArrayList<IJsonTransformerProvider>();
        for (var provider : transformerProviders) {
            log.atInfo().setMessage("Adding IJsonTransfomerProvider: {}").addArgument(provider).log();
            inProgressProviders.add(provider);
        }
        providers = Collections.unmodifiableList(inProgressProviders);
        log.atInfo().setMessage("IJsonTransformerProviders loaded: {}")
            .addArgument(() -> providers.stream().map(p -> p.getClass().toString())
                .collect(Collectors.joining("; ")))
            .log();
    }

    List<Map<String, Object>> parseFullConfig(String fullConfig) throws JsonProcessingException {
        if (CLASS_NAME_PATTERN.matcher(fullConfig).matches()) {
            return List.of(Collections.singletonMap(fullConfig, null));
        } else {
            return objMapper.readValue(fullConfig, new TypeReference<>() {
            });
        }
    }

    public Stream<IJsonTransformer> getTransformerFactoryFromServiceLoader(String fullConfig)
        throws JsonProcessingException {
        var configList = fullConfig == null ? List.of() : parseFullConfig(fullConfig);
        if (configList.isEmpty() || providers.isEmpty()) {
            log.trace("No transformer configuration specified.  No custom transformations will be performed");
            return Stream.of();
        } else {
            return configList.stream().map(c -> configureTransformerFromConfig((Map<String, Object>) c));
        }
    }

    public Stream<IJsonTransformer> getTransformerFactoryFromServiceLoaderParsed(List<Object> configList) {
        if (configList.isEmpty() || providers.isEmpty()) {
            log.trace("No transformer configuration specified.  No custom transformations will be performed");
            return Stream.of();
        } else {
            return configList.stream().map(c -> configureTransformerFromConfig((Map<String, Object>) c));
        }
    }

    @SneakyThrows // JsonProcessingException should be impossible since the contents are those that were just parsed
    private IJsonTransformer configureTransformerFromConfig(Map<String, Object> c) {
        var keys = c.keySet();
        if (keys.size() != 1) {
            throw new IllegalArgumentException(WRONG_JSON_STRUCTURE_MESSAGE);
        }
        var key = keys.stream()
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(WRONG_JSON_STRUCTURE_MESSAGE));
        for (var p : providers) {
            var transformerName = p.getName();
            if (transformerName.equals(key)) {
                var configuration = c.get(key);
                log.atInfo().setMessage("Creating a transformer through provider={} with configuration={}")
                    .addArgument(p)
                    .addArgument(configuration)
                    .log();
                return p.createTransformer(resolveProviderConfig(configuration, p));
            }
        }
        throw new IllegalArgumentException("Could not find a provider named: " + key);
    }

    @SuppressWarnings("unchecked")
    private Object resolveProviderConfig(Object configuration, IJsonTransformerProvider provider) throws IOException {
        if (!(configuration instanceof Map)) {
            return configuration;
        }
        var config = (Map<String, Object>) configuration;
        if (!config.containsKey(PROVIDER_CONFIG_DIRS_KEY) && !config.containsKey(PROVIDER_CONFIG_FILES_KEY)) {
            return configuration;
        }

        /*
         * File-backed provider config is flattened into the provider's config map before
         * createTransformer is called.
         *
         * Merge order is intentional and last-writer-wins for conflicting keys:
         *   1. providerConfigDirs, in the order supplied. Within each directory, regular
         *      files are processed by file name for deterministic output. The file name is
         *      the config key.
         *   2. providerConfigFiles. The map key is the config key.
         *   3. Inline provider config fields from the original config map, excluding
         *      providerConfigDirs/providerConfigFiles.
         *
         * Practically, this means explicit inline config overrides any mounted/defaulted
         * value, and individual file references override directory-loaded values.
         * Duplicate keys inside one JSON object are not a supported conflict mechanism;
         * once the JSON is parsed, each map has one value per key.
         */
        var resolved = new LinkedHashMap<String, Object>();
        resolved.putAll(readDirectoryValues(config.get(PROVIDER_CONFIG_DIRS_KEY), provider));
        resolved.putAll(readFileValues(config.get(PROVIDER_CONFIG_FILES_KEY), provider));
        config.forEach((key, value) -> {
            if (!PROVIDER_CONFIG_DIRS_KEY.equals(key) && !PROVIDER_CONFIG_FILES_KEY.equals(key)) {
                resolved.put(key, value);
            }
        });
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDirectoryValues(
        Object directoriesConfig,
        IJsonTransformerProvider provider
    ) throws IOException {
        if (directoriesConfig == null) {
            return Collections.emptyMap();
        }
        if (!(directoriesConfig instanceof List)) {
            throw new IllegalArgumentException(PROVIDER_CONFIG_DIRS_KEY + " must be a list.");
        }

        var result = new LinkedHashMap<String, Object>();
        for (var directoryConfig : (List<Object>) directoriesConfig) {
            var directoryPath = getRequiredPath(directoryConfig, PROVIDER_CONFIG_DIRS_KEY);
            try (var entries = Files.list(directoryPath)) {
                for (var file : entries
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                    var key = file.getFileName().toString();
                    result.put(key, materializePathValue(file, provider.getFileBackedConfigValueType(key)));
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFileValues(
        Object filesConfig,
        IJsonTransformerProvider provider
    ) throws IOException {
        if (filesConfig == null) {
            return Collections.emptyMap();
        }
        if (!(filesConfig instanceof Map)) {
            throw new IllegalArgumentException(PROVIDER_CONFIG_FILES_KEY + " must be a map.");
        }

        var result = new LinkedHashMap<String, Object>();
        for (var entry : ((Map<String, Object>) filesConfig).entrySet()) {
            var path = getRequiredPath(entry.getValue(), PROVIDER_CONFIG_FILES_KEY + "." + entry.getKey());
            result.put(entry.getKey(), materializePathValue(path, provider.getFileBackedConfigValueType(entry.getKey())));
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

    private Object materializePathValue(Path path, ConfigFileValueType valueType) throws IOException {
        return switch (valueType) {
            case JSON -> objMapper.readValue(Files.readString(path, StandardCharsets.UTF_8), Object.class);
            case TEXT -> Files.readString(path, StandardCharsets.UTF_8);
            case BYTES -> Files.readAllBytes(path);
            case BASE64 -> Base64.getEncoder().encodeToString(Files.readAllBytes(path));
            case PATH -> path.toString();
        };
    }

    public IJsonTransformer getTransformerFactoryLoaderWithNewHostName(String newHostName) {
        return getTransformerFactoryLoader(newHostName, null, null);
    }

    public IJsonTransformer getTransformerFactoryLoader(String fullConfig) {
        return getTransformerFactoryLoader(null, null, fullConfig);
    }

    public IJsonTransformer getTransformerFactoryLoader(String newHostName, String userAgent, String fullConfig) {
        try {
            var loadedTransformers = getTransformerFactoryFromServiceLoader(fullConfig);
            return new JsonCompositeTransformer(
                Stream.concat(
                    loadedTransformers,
                    Stream.concat(
                        Optional.ofNullable(userAgent).stream().map(UserAgentTransformer::new),
                        Optional.ofNullable(newHostName).stream().map(HostTransformer::new)
                    )
                ).toArray(IJsonTransformer[]::new)
            );
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not parse the transformer configuration as a json list", e);
        }
    }

    @AllArgsConstructor
    private static class UserAgentTransformer implements IJsonTransformer {
        public static final String USER_AGENT = "user-agent";
        private final String userAgent;

        @Override
        public Object transformJson(Object incomingJson) {
            @SuppressWarnings("unchecked")
            var headers = (Map<String, Object>) ((Map<String, Object>) incomingJson).get(JsonKeysForHttpMessage.HEADERS_KEY);
            var oldVal = headers.get(USER_AGENT);
            if (oldVal != null) {
                if (oldVal instanceof List) {
                    // see https://www.rfc-editor.org/rfc/rfc9110.html#name-field-lines-and-combined-fi
                    oldVal = String.join(", ", (List<String>) oldVal);
                }
                headers.replace(USER_AGENT, oldVal + "; " + userAgent);
            } else {
                headers.put(USER_AGENT, userAgent);
            }
            return incomingJson;
        }
    }

    @AllArgsConstructor
    private static class HostTransformer implements IJsonTransformer {
        private final String newHostName;

        @Override
        public Object transformJson(Object incomingJson) {
            @SuppressWarnings("unchecked")
            var headers = (Map<String, Object>) ((Map<String, Object>) incomingJson).get(JsonKeysForHttpMessage.HEADERS_KEY);
            if (headers != null) {
                headers.replace("host", newHostName);
            } else {
                log.atDebug().setMessage("Host header is null in incoming message: {}").addArgument(incomingJson).log();
            }
            return incomingJson;
        }
    }
}
