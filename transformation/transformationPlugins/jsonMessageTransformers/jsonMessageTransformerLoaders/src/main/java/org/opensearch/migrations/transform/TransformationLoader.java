package org.opensearch.migrations.transform;

import java.util.ArrayList;
import java.util.Collections;
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
            log.warn("No transformer configuration specified.  No custom transformations will be performed");
            return Stream.of();
        } else {
            return configList.stream().map(c -> configureTransformerFromConfig((Map<String, Object>) c));
        }
    }

    public Stream<IJsonTransformer> getTransformerFactoryFromServiceLoaderParsed(List<Object> configList) {
        if (configList.isEmpty() || providers.isEmpty()) {
            log.warn("No transformer configuration specified.  No custom transformations will be performed");
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
                return p.createTransformer(configuration);
            }
        }
        throw new IllegalArgumentException("Could not find a provider named: " + key);
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
