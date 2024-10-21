package org.opensearch.migrations.transform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.opensearch.migrations.transform.JsonCompositePrecondition.CompositeOperation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PreconditionLoader {
    public static final String WRONG_JSON_STRUCTURE_MESSAGE =
        "Must specify the top-level configuration list with a sequence of "
            + "maps that have only one key each, where the key is the name of the transformer to be configured.";
    public static final Pattern CLASS_NAME_PATTERN = Pattern.compile("^[^{}]*$");
    private final List<IJsonPreconditionProvider> providers;
    ObjectMapper objMapper = new ObjectMapper();

    public PreconditionLoader() {
        ServiceLoader<IJsonPreconditionProvider> transformerProviders = ServiceLoader.load(
            IJsonPreconditionProvider.class
        );
        var inProgressProviders = new ArrayList<IJsonPreconditionProvider>();
        for (var provider : transformerProviders) {
            log.info("Adding IJsonPreconditionProvider: " + provider);
            inProgressProviders.add(provider);
        }
        providers = Collections.unmodifiableList(inProgressProviders);
        log.atInfo()
            .setMessage(
                () -> "IJsonPreconditionProvider loaded: "
                    + providers.stream().map(p -> p.getClass().toString()).collect(Collectors.joining("; "))
            )
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

    protected Stream<IJsonPrecondition> getTransformerFactoryFromServiceLoader(String fullConfig)
        throws JsonProcessingException {
        var configList = fullConfig == null ? List.of() : parseFullConfig(fullConfig);
        if (configList.isEmpty() || providers.isEmpty()) {
            log.warn("No transformer configuration specified.  No custom transformations will be performed");
            return Stream.of();
        } else {
            return configList.stream().map(c -> configureTransformerFromConfig((Map<String, Object>) c));
        }
    }

    public Stream<IJsonPrecondition> getTransformerFactoryFromServiceLoaderParsed(List<Object> configList) {
        if (configList.isEmpty() || providers.isEmpty()) {
            log.warn("No transformer configuration specified.  No custom transformations will be performed");
            return Stream.of();
        } else {
            return configList.stream().map(c -> configureTransformerFromConfig((Map<String, Object>) c));
        }
    }


    @SneakyThrows // JsonProcessingException should be impossible since the contents are those that were just parsed
    private IJsonPrecondition configureTransformerFromConfig(Map<String, Object> c) {
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
                log.atInfo()
                    .setMessage(
                        () -> "Creating a transformer through provider=" + p + " with configuration=" + configuration
                    )
                    .log();
                return p.createPrecondition(configuration);
            }
        }
        throw new IllegalArgumentException("Could not find a provider named: " + key);
    }

    public IJsonPrecondition getTransformerFactoryLoader(String fullConfig) {
        try {
            var loadedTransformers = getTransformerFactoryFromServiceLoader(fullConfig);
            return new JsonCompositePrecondition(
                CompositeOperation.ALL,
                loadedTransformers.toArray(IJsonPrecondition[]::new)
            );
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not parse the transformer configuration as a json list", e);
        }
    }
}
