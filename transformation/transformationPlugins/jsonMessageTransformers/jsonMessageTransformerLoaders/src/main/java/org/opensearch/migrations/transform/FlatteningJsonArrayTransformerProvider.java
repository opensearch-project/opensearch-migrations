package org.opensearch.migrations.transform;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FlatteningJsonArrayTransformerProvider implements IJsonTransformerProvider {

    @Override
    @SneakyThrows
    public IJsonTransformer createTransformer(Object jsonConfig) {
        if (!(jsonConfig instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(getConfigUsageStr());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> configMap = (Map<String, Object>) jsonConfig;

        return Optional.of(configMap)
                .filter(config -> config.size() == 1)
                .map(this::extractTransformer)
                .orElseThrow(() -> new IllegalArgumentException(getConfigUsageStr()));
    }

    private IJsonTransformer extractTransformer(Map<String, Object> config) {
        var transformerList = new TransformationLoader()
                .getTransformerFactoryFromServiceLoaderParsed(List.of(config))
                .toList();

        if (transformerList.size() != 1) {
            throw new IllegalStateException("Expected exactly one transformer, but found: " + transformerList.size());
        }

        return new FlatteningJsonArrayTransformer(transformerList.get(0));
    }

    private String getConfigUsageStr() {
        return String.format(
                "%s expects a configuration of type Map<String, Object> with exactly one entry, "
                        + "where the key is the transformer class name and the value is its configuration.",
                this.getClass().getName()
        );
    }
}
