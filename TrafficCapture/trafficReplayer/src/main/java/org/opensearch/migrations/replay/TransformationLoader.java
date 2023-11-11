package org.opensearch.migrations.replay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.opensearch.migrations.transform.IJsonTransformerProvider;
import org.opensearch.migrations.transform.JsonCompositeTransformer;
import org.opensearch.migrations.transform.JsonKeysForHttpMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class TransformationLoader {
    public static final String WRONG_JSON_STRUCTURE_MESSAGE = "Must specify the top-level configuration list with a sequence of " +
            "maps that have only one key each, where the key is the name of the transformer to be configured.";
    private final List<IJsonTransformerProvider> providers;
    ObjectMapper objMapper = new ObjectMapper();

    public TransformationLoader() {
        ServiceLoader<IJsonTransformerProvider> transformerProviders =
                ServiceLoader.load(IJsonTransformerProvider.class);
        var inProgressProviders = new ArrayList<IJsonTransformerProvider>();
        for (var provider : transformerProviders) {
            log.info("Adding IJsonTransfomerProvider: " + provider);
            inProgressProviders.add(provider);
        }
        providers = Collections.unmodifiableList(inProgressProviders);
        log.atInfo().setMessage(()->"IJsonTransformerProviders loaded: " +
                providers.stream().map(p->p.getClass().toString()).collect(Collectors.joining("; "))).log();
    }

    List<Map<String, Object>> parseFullConfig(String fullConfig) throws JsonProcessingException {
        return objMapper.readValue(fullConfig, new TypeReference<>() {});
    }

    protected Stream<IJsonTransformer> getTransformerFactoryFromServiceLoader(String fullConfig)
            throws JsonProcessingException {
        var configList = fullConfig == null ? List.of() : parseFullConfig(fullConfig);
        if (providers.size() > 1 && configList.isEmpty()) {
            throw new IllegalArgumentException("Must provide a configuration when multiple IJsonTransformerProvider " +
                    "are loaded (" + providers.stream().map(p -> p.getClass().toString())
                    .collect(Collectors.joining(",")) + ")");
        } else if (providers.isEmpty()) {
            return Stream.of();
        } else if (!configList.isEmpty()) {
            return configList.stream().map(c -> configureTransformerFromConfig((Map<String, Object>) c));
        } else {
            // send in Optional.empty because we would have hit the other case in the previous branch
            return Stream.of(providers.get(0).createTransformer(Optional.empty()));
        }
    }

    @SneakyThrows // JsonProcessingException should be impossible since the contents are those that were just parsed
    private IJsonTransformer configureTransformerFromConfig(Map<String, Object> c) {
        var keys = c.keySet();
        if (keys.size() != 1) {
            throw new IllegalArgumentException(WRONG_JSON_STRUCTURE_MESSAGE);
        }
        var key = keys.stream().findFirst()
                .orElseThrow(()->new IllegalArgumentException(WRONG_JSON_STRUCTURE_MESSAGE));
        for (var p : providers) {
            var className = p.getClass().getName();
            if (className.equals(key)) {
                var configuration = c.get(key);
                log.atInfo().setMessage(()->"Creating a transformer with configuration="+configuration).log();
                return p.createTransformer(configuration);
            }
        }
        throw new IllegalArgumentException("Could not find a provider named: " + key);
    }
    public IJsonTransformer getTransformerFactoryLoader(String newHostName) {
        return getTransformerFactoryLoader(newHostName, null);
    }

    public IJsonTransformer getTransformerFactoryLoader(String newHostName, String fullConfig) {
        try {
            var loadedTransformers = getTransformerFactoryFromServiceLoader(fullConfig);
            return new JsonCompositeTransformer(Stream.concat(
                    loadedTransformers,
                    Optional.ofNullable(newHostName).map(h->Stream.of(new HostTransformer(h))).orElse(Stream.of())
            ).toArray(IJsonTransformer[]::new));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Could not parse the transformer configuration as a json list", e);
        }
    }

    private class HostTransformer implements IJsonTransformer {
        private final String newHostName;

        @Override
        public Map<String,Object> transformJson(Map<String,Object> incomingJson) {
            var asMap = (Map<String, Object>) incomingJson;
            var headers = (Map<String, Object>) asMap.get(JsonKeysForHttpMessage.HEADERS_KEY);
            headers.replace("host", newHostName);
            return asMap;
        }

        public HostTransformer(String newHostName) {
            this.newHostName = newHostName;
        }

    }
}
