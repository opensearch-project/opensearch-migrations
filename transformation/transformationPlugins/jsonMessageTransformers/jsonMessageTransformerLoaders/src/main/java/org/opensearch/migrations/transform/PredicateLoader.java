package org.opensearch.migrations.transform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PredicateLoader {
    public static final String WRONG_JSON_STRUCTURE_MESSAGE =
        "Must specify the top-level configuration map"
            + " that only has one key, where the key is the name of the Predicate to be configured.";
    public static final Pattern CLASS_NAME_PATTERN = Pattern.compile("^[^{}]*$");
    private final List<IJsonPredicateProvider> providers;
    ObjectMapper objMapper = new ObjectMapper();

    public PredicateLoader() {
        ServiceLoader<IJsonPredicateProvider> predicateProviders = ServiceLoader.load(
            IJsonPredicateProvider.class
        );
        var inProgressProviders = new ArrayList<IJsonPredicateProvider>();
        for (var provider : predicateProviders) {
            log.atInfo().setMessage("Adding IJsonPredicateProvider: {}").addArgument(provider).log();
            inProgressProviders.add(provider);
        }
        providers = Collections.unmodifiableList(inProgressProviders);
        log.atInfo()
            .setMessage(() -> "IJsonPredicateProvider loaded: {}")
            .addArgument(() -> providers.stream().map(p -> p.getClass().toString()).collect(Collectors.joining("; ")))
            .log();
    }

    Map<String, Object> parseFullConfig(String fullConfig) throws JsonProcessingException {
        if (CLASS_NAME_PATTERN.matcher(fullConfig).matches()) {
            return Collections.singletonMap(fullConfig, null);
        } else {
            return objMapper.readValue(fullConfig, new TypeReference<>() {});
        }
    }

    protected IJsonPredicate getPredicateFactoryFromServiceLoader(String fullConfig)
        throws JsonProcessingException {
        Map<String, Object> configMap = fullConfig == null ? Map.of() : parseFullConfig(fullConfig);
        return getPredicateFactoryFromServiceLoaderParsed(configMap);
    }

    public IJsonPredicate getPredicateFactoryFromServiceLoaderParsed(Object config) {
        @SuppressWarnings("unchecked")
        var configMap = (Map<String, Object>) config;
        if (configMap.isEmpty() || providers.isEmpty()) {
            log.atError().setMessage("No predicate configuration found for configuration {}")
                    .addArgument(config).log();
            throw new IllegalArgumentException("No predicate configuration found for configuration " + config);
        } else {
            return configurePredicateFromConfig(configMap);
        }
    }

    @SneakyThrows // JsonProcessingException should be impossible since the contents are those that were just parsed
    private IJsonPredicate configurePredicateFromConfig(Map<String, Object> c) {
        var keys = c.keySet();
        if (keys.size() != 1) {
            throw new IllegalArgumentException(WRONG_JSON_STRUCTURE_MESSAGE);
        }
        var key = keys.stream()
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(WRONG_JSON_STRUCTURE_MESSAGE));
        for (var p : providers) {
            var predicateName = p.getName();
            if (predicateName.equals(key)) {
                var configuration = c.get(key);
                log.atInfo()
                    .setMessage("Creating a Predicate through provider={} with configuration={}")
                    .addArgument(p)
                    .addArgument(configuration)
                    .log();
                return p.createPredicate(configuration);
            }
        }
        throw new IllegalArgumentException("Could not find a provider named: " + key);
    }

    public IJsonPredicate getPredicateFactoryLoader(String fullConfig) {
        try {
            return getPredicateFactoryFromServiceLoader(fullConfig);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not parse the Predicate configuration as a json list", e);
        }
    }
}
